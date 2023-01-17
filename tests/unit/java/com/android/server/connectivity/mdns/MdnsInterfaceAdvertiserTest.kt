/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity.mdns

import android.net.InetAddresses.parseNumericAddress
import android.net.LinkAddress
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.HandlerThread
import com.android.server.connectivity.mdns.MdnsAnnouncer.AnnouncementInfo
import com.android.server.connectivity.mdns.MdnsAnnouncer.BaseAnnouncementInfo
import com.android.server.connectivity.mdns.MdnsAnnouncer.ExitAnnouncementInfo
import com.android.server.connectivity.mdns.MdnsInterfaceAdvertiser.EXIT_ANNOUNCEMENT_DELAY_MS
import com.android.server.connectivity.mdns.MdnsPacketRepeater.PacketRepeaterCallback
import com.android.server.connectivity.mdns.MdnsProber.ProbingInfo
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.waitForIdle
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

private const val LOG_TAG = "testlogtag"
private const val TIMEOUT_MS = 10_000L

private val TEST_ADDRS = listOf(LinkAddress(parseNumericAddress("2001:db8::123"), 64))
private val TEST_BUFFER = ByteArray(1300)

private const val TEST_SERVICE_ID_1 = 42
private val TEST_SERVICE_1 = NsdServiceInfo().apply {
    serviceType = "_testservice._tcp"
    serviceName = "MyTestService"
    port = 12345
}

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsInterfaceAdvertiserTest {
    private val socket = mock(MdnsInterfaceSocket::class.java)
    private val thread = HandlerThread(MdnsInterfaceAdvertiserTest::class.simpleName)
    private val cb = mock(MdnsInterfaceAdvertiser.Callback::class.java)
    private val deps = mock(MdnsInterfaceAdvertiser.Dependencies::class.java)
    private val repository = mock(MdnsRecordRepository::class.java)
    private val replySender = mock(MdnsReplySender::class.java)
    private val announcer = mock(MdnsAnnouncer::class.java)
    private val prober = mock(MdnsProber::class.java)
    private val probeCbCaptor = ArgumentCaptor.forClass(PacketRepeaterCallback::class.java)
            as ArgumentCaptor<PacketRepeaterCallback<ProbingInfo>>
    private val announceCbCaptor = ArgumentCaptor.forClass(PacketRepeaterCallback::class.java)
            as ArgumentCaptor<PacketRepeaterCallback<BaseAnnouncementInfo>>

    private val probeCb get() = probeCbCaptor.value
    private val announceCb get() = announceCbCaptor.value

    private val advertiser by lazy {
        MdnsInterfaceAdvertiser(LOG_TAG, socket, TEST_ADDRS, thread.looper, TEST_BUFFER, cb, deps)
    }

    @Before
    fun setUp() {
        doReturn(repository).`when`(deps).makeRecordRepository(any())
        doReturn(replySender).`when`(deps).makeReplySender(any(), any(), any())
        doReturn(announcer).`when`(deps).makeMdnsAnnouncer(any(), any(), any(), any())
        doReturn(prober).`when`(deps).makeMdnsProber(any(), any(), any(), any())

        val knownServices = mutableSetOf<Int>()
        doAnswer { inv ->
            knownServices.add(inv.getArgument(0))
            -1
        }.`when`(repository).addService(anyInt(), any())
        doAnswer { inv ->
            knownServices.remove(inv.getArgument(0))
            null
        }.`when`(repository).removeService(anyInt())
        doAnswer {
            knownServices.toIntArray().also { knownServices.clear() }
        }.`when`(repository).clearServices()
        doAnswer { inv ->
            knownServices.contains(inv.getArgument(0))
        }.`when`(repository).hasActiveService(anyInt())
        thread.start()
        advertiser.start()

        verify(deps).makeMdnsProber(any(), any(), any(), probeCbCaptor.capture())
        verify(deps).makeMdnsAnnouncer(any(), any(), any(), announceCbCaptor.capture())
    }

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    @Test
    fun testAddRemoveService() {
        val testAnnouncementInfo = addServiceAndFinishProbing(TEST_SERVICE_ID_1, TEST_SERVICE_1)

        verify(announcer).startSending(TEST_SERVICE_ID_1, testAnnouncementInfo,
                0L /* initialDelayMs */)

        thread.waitForIdle(TIMEOUT_MS)
        verify(cb).onRegisterServiceSucceeded(advertiser, TEST_SERVICE_ID_1)

        // Remove the service: expect exit announcements
        val testExitInfo = mock(ExitAnnouncementInfo::class.java)
        doReturn(testExitInfo).`when`(repository).exitService(TEST_SERVICE_ID_1)
        advertiser.removeService(TEST_SERVICE_ID_1)

        verify(prober).stop(TEST_SERVICE_ID_1)
        verify(announcer).stop(TEST_SERVICE_ID_1)
        verify(announcer).startSending(TEST_SERVICE_ID_1, testExitInfo, EXIT_ANNOUNCEMENT_DELAY_MS)

        // Exit announcements finish: the advertiser has no left service and destroys itself
        announceCb.onFinished(testExitInfo)
        thread.waitForIdle(TIMEOUT_MS)
        verify(cb).onDestroyed(socket)
    }

    @Test
    fun testDoubleRemove() {
        addServiceAndFinishProbing(TEST_SERVICE_ID_1, TEST_SERVICE_1)

        val testExitInfo = mock(ExitAnnouncementInfo::class.java)
        doReturn(testExitInfo).`when`(repository).exitService(TEST_SERVICE_ID_1)
        advertiser.removeService(TEST_SERVICE_ID_1)

        verify(prober).stop(TEST_SERVICE_ID_1)
        verify(announcer).stop(TEST_SERVICE_ID_1)
        verify(announcer).startSending(TEST_SERVICE_ID_1, testExitInfo, EXIT_ANNOUNCEMENT_DELAY_MS)

        doReturn(false).`when`(repository).hasActiveService(TEST_SERVICE_ID_1)
        advertiser.removeService(TEST_SERVICE_ID_1)
        // Prober, announcer were still stopped only one time
        verify(prober, times(1)).stop(TEST_SERVICE_ID_1)
        verify(announcer, times(1)).stop(TEST_SERVICE_ID_1)
    }

    private fun addServiceAndFinishProbing(serviceId: Int, serviceInfo: NsdServiceInfo):
            AnnouncementInfo {
        val testProbingInfo = mock(ProbingInfo::class.java)
        doReturn(serviceId).`when`(testProbingInfo).serviceId
        doReturn(testProbingInfo).`when`(repository).setServiceProbing(serviceId)

        advertiser.addService(serviceId, serviceInfo)
        verify(repository).addService(serviceId, serviceInfo)
        verify(prober).startProbing(testProbingInfo)

        // Simulate probing success: continues to announcing
        val testAnnouncementInfo = mock(AnnouncementInfo::class.java)
        doReturn(testAnnouncementInfo).`when`(repository).onProbingSucceeded(testProbingInfo)
        probeCb.onFinished(testProbingInfo)
        return testAnnouncementInfo
    }
}