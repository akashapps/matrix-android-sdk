/*
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.roomkeys

import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import org.junit.Assert
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.matrix.androidsdk.common.CommonTestHelper
import org.matrix.androidsdk.common.SessionTestParams
import org.matrix.androidsdk.common.TestApiCallback
import org.matrix.androidsdk.common.TestConstants
import org.matrix.androidsdk.crypto.MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
import org.matrix.androidsdk.crypto.keysbackup.MegolmBackupAuthData
import org.matrix.androidsdk.rest.model.MatrixError
import org.matrix.androidsdk.rest.model.keys.*
import org.matrix.androidsdk.util.JsonUtils
import org.matrix.androidsdk.util.Log
import java.util.*
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.JVM)
class RoomKeysRestClientTest {

    private val mTestHelper = CommonTestHelper()

    @Test
    fun roomKeysTest_getKeysBackupVersion_noBackup() {
        Log.e(LOG_TAG, "RoomKeysTest_getVersion")

        val context = InstrumentationRegistry.getContext()
        val bobSession = mTestHelper.createAccount(TestConstants.USER_BOB, SessionTestParams.newBuilder().build())

        val lock = CountDownLatch(1)
        bobSession.roomKeysRestClient
                .getKeysBackupVersion(null, object : TestApiCallback<KeysVersionResult>(lock, false) {
                    override fun onMatrixError(e: MatrixError) {
                        Assert.assertEquals(MatrixError.NOT_FOUND, e.errcode)
                        super.onMatrixError(e)
                    }
                })
        mTestHelper.await(lock)

        bobSession.clear(context)
    }

    /**
     * - Create a backup version on the server
     * - Get the current version from the server
     * -> Check they match
     */
    @Test
    fun roomKeysTest_createVersionAndRetrieveIt_ok() {
        Log.e(LOG_TAG, "RoomKeysTest_createVersion_ok")

        val context = InstrumentationRegistry.getContext()
        val bobSession = mTestHelper.createAccount(TestConstants.USER_BOB, SessionTestParams.newBuilder().build())

        val megolmBackupAuthData = createFakeMegolmBackupAuthData()

        val createKeysBackupVersionBody = CreateKeysBackupVersionBody()
        createKeysBackupVersionBody.algorithm = MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
        createKeysBackupVersionBody.authData = JsonUtils.getGson(false).toJsonTree(megolmBackupAuthData)

        var keysVersion: KeysVersion? = null
        var lock = CountDownLatch(1)
        bobSession.roomKeysRestClient
                .createKeysBackupVersion(createKeysBackupVersionBody, object : TestApiCallback<KeysVersion>(lock) {
                    override fun onSuccess(info: KeysVersion) {
                        keysVersion = info
                        super.onSuccess(info)
                    }
                })
        mTestHelper.await(lock)

        Assert.assertNotNull(keysVersion)

        val version = keysVersion!!.version
        Assert.assertNotNull(version)

        var keysVersionResult: KeysVersionResult? = null
        lock = CountDownLatch(1)
        // Retrieve the last version and check we get the same content
        bobSession.roomKeysRestClient
                .getKeysBackupVersion(null, object : TestApiCallback<KeysVersionResult>(lock) {
                    override fun onSuccess(info: KeysVersionResult) {
                        keysVersionResult = info
                        super.onSuccess(info)
                    }
                })
        mTestHelper.await(lock)

        // Check that all the fields are the same
        Assert.assertNotNull(keysVersionResult)
        Assert.assertEquals(version, keysVersionResult!!.version)
        Assert.assertEquals(createKeysBackupVersionBody.algorithm, keysVersionResult!!.algorithm)

        val retrievedMegolmBackupAuthData = keysVersionResult!!.getAuthDataAsMegolmBackupAuthData()

        Assert.assertEquals(megolmBackupAuthData.publicKey, retrievedMegolmBackupAuthData.publicKey)
        Assert.assertEquals(megolmBackupAuthData.signatures, retrievedMegolmBackupAuthData.signatures)

        bobSession.clear(context)
    }

    /**
     * - Create a backup version on the server
     * - Make a backup
     * - Get the backup back
     * -> Check they match
     */
    @Test
    fun roomKeysTest_createVersionCreateBackupAndRetrieveIt_ok() {
        Log.e(LOG_TAG, "RoomKeysTest_createVersion_ok")

        val context = InstrumentationRegistry.getContext()
        val bobSession = mTestHelper.createAccount(TestConstants.USER_BOB, SessionTestParams.newBuilder().build())

        val megolmBackupAuthData = createFakeMegolmBackupAuthData()

        val createKeysBackupVersionBody = CreateKeysBackupVersionBody()
        createKeysBackupVersionBody.algorithm = MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
        createKeysBackupVersionBody.authData = JsonUtils.getGson(false).toJsonTree(megolmBackupAuthData)

        var keysVersion: KeysVersion? = null
        var lock = CountDownLatch(1)
        bobSession.roomKeysRestClient
                .createKeysBackupVersion(createKeysBackupVersionBody, object : TestApiCallback<KeysVersion>(lock) {
                    override fun onSuccess(info: KeysVersion) {
                        keysVersion = info
                        super.onSuccess(info)
                    }
                })
        mTestHelper.await(lock)

        Assert.assertNotNull(keysVersion)
        val version = keysVersion!!.version
        Assert.assertNotNull(version)

        // Make a backup
        val keys = HashMap<String, String>()
        keys["key"] = "value"

        val keyBackupData = KeyBackupData()
        keyBackupData.firstMessageIndex = 1
        keyBackupData.forwardedCount = 2
        keyBackupData.isVerified = true
        keyBackupData.sessionData = JsonUtils.getGson(false).toJsonTree(keys)

        val roomId = "!aRoomId:matrix.org"
        val sessionId = "ASession"

        lock = CountDownLatch(1)
        // Send the backup
        bobSession.roomKeysRestClient
                .sendKeyBackup(roomId, sessionId, version!!, keyBackupData, TestApiCallback(lock))
        mTestHelper.await(lock)

        // Get the backup back
        var keysBackupDataResult: KeysBackupData? = null
        lock = CountDownLatch(1)
        // Retrieve the version and check we get the same content
        bobSession.roomKeysRestClient
                .getKeysBackup(version, object : TestApiCallback<KeysBackupData>(lock) {
                    override fun onSuccess(info: KeysBackupData) {
                        keysBackupDataResult = info
                        super.onSuccess(info)
                    }
                })
        mTestHelper.await(lock)

        // Check that all the fields are the same
        Assert.assertNotNull(keysBackupDataResult)
        val retrievedKeyBackupData = keysBackupDataResult!!.roomIdToRoomKeysBackupData[roomId]!!.sessionIdToKeyBackupData[sessionId]!!

        Assert.assertEquals(keyBackupData.firstMessageIndex, retrievedKeyBackupData.firstMessageIndex)
        Assert.assertEquals(keyBackupData.forwardedCount, retrievedKeyBackupData.forwardedCount)
        Assert.assertEquals(keyBackupData.isVerified, retrievedKeyBackupData.isVerified)
        Assert.assertEquals(keyBackupData.sessionData, retrievedKeyBackupData.sessionData)

        bobSession.clear(context)
    }

    /**
     * - Create a backup version on the server
     * - Make a backup
     * - Delete it
     * - Get the backup back
     * -> Check it is now empty
     */
    @Test
    fun roomKeysTest_createVersionCreateBackupDeleteBackupAndRetrieveIt_ok() {
        Log.e(LOG_TAG, "RoomKeysTest_createVersion_ok")

        val context = InstrumentationRegistry.getContext()
        val bobSession = mTestHelper.createAccount(TestConstants.USER_BOB, SessionTestParams.newBuilder().build())

        val megolmBackupAuthData = createFakeMegolmBackupAuthData()

        val createKeysBackupVersionBody = CreateKeysBackupVersionBody()
        createKeysBackupVersionBody.algorithm = MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
        createKeysBackupVersionBody.authData = JsonUtils.getGson(false).toJsonTree(megolmBackupAuthData)

        var keysVersion: KeysVersion? = null
        var lock = CountDownLatch(1)
        bobSession.roomKeysRestClient
                .createKeysBackupVersion(createKeysBackupVersionBody, object : TestApiCallback<KeysVersion>(lock) {
                    override fun onSuccess(info: KeysVersion) {
                        keysVersion = info
                        super.onSuccess(info)
                    }
                })
        mTestHelper.await(lock)

        Assert.assertNotNull(keysVersion)
        val version = keysVersion!!.version
        Assert.assertNotNull(version)

        // Make a backup
        val keys = HashMap<String, String>()
        keys["key"] = "value"

        val keyBackupData = KeyBackupData()
        keyBackupData.firstMessageIndex = 1
        keyBackupData.forwardedCount = 2
        keyBackupData.isVerified = true
        keyBackupData.sessionData = JsonUtils.getGson(false).toJsonTree(keys)

        val roomId = "!aRoomId:matrix.org"
        val sessionId = "ASession"

        // Send the backup
        lock = CountDownLatch(1)
        bobSession.roomKeysRestClient
                .sendKeyBackup(roomId, sessionId, version!!, keyBackupData, TestApiCallback(lock))
        mTestHelper.await(lock)

        // Delete the backup
        lock = CountDownLatch(1)
        bobSession.roomKeysRestClient
                .deleteKeyBackup(roomId, sessionId, version, TestApiCallback(lock))
        mTestHelper.await(lock)

        // Get the backup back
        var keysBackupDataResult: KeysBackupData? = null
        lock = CountDownLatch(1)
        // Retrieve the version and check it is empty
        bobSession.roomKeysRestClient
                .getKeysBackup(version, object : TestApiCallback<KeysBackupData>(lock) {
                    override fun onSuccess(info: KeysBackupData) {
                        keysBackupDataResult = info
                        super.onSuccess(info)
                    }
                })
        mTestHelper.await(lock)

        // Check that all the fields are the same
        Assert.assertNotNull(keysBackupDataResult)
        Assert.assertTrue(keysBackupDataResult!!.roomIdToRoomKeysBackupData.isEmpty())

        bobSession.clear(context)
    }

    /* ==========================================================================================
     * Private
     * ========================================================================================== */

    private fun createFakeMegolmBackupAuthData(): MegolmBackupAuthData {
        return MegolmBackupAuthData().apply {
            publicKey = "abcdefg"
            signatures = HashMap<String, Any>().apply {
                this["something"] = HashMap<String, String>().apply {
                    this["ed25519:something"] = "hijklmnop"
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = RoomKeysRestClientTest::class.java.simpleName
    }
}
