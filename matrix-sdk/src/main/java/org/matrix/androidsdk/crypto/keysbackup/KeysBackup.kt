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

package org.matrix.androidsdk.crypto.keysbackup

import android.support.annotation.VisibleForTesting
import org.matrix.androidsdk.MXSession
import org.matrix.androidsdk.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.androidsdk.crypto.MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
import org.matrix.androidsdk.crypto.MXCrypto
import org.matrix.androidsdk.crypto.MegolmSessionData
import org.matrix.androidsdk.crypto.data.ImportRoomKeysResult
import org.matrix.androidsdk.crypto.data.MXOlmInboundGroupSession2
import org.matrix.androidsdk.crypto.util.computeRecoveryKey
import org.matrix.androidsdk.crypto.util.extractCurveKeyFromRecoveryKey
import org.matrix.androidsdk.rest.callback.ApiCallback
import org.matrix.androidsdk.rest.callback.SimpleApiCallback
import org.matrix.androidsdk.rest.callback.SuccessErrorCallback
import org.matrix.androidsdk.rest.client.RoomKeysRestClient
import org.matrix.androidsdk.rest.model.MatrixError
import org.matrix.androidsdk.rest.model.keys.*
import org.matrix.androidsdk.util.JsonUtils
import org.matrix.androidsdk.util.Log
import org.matrix.olm.OlmException
import org.matrix.olm.OlmPkDecryption
import org.matrix.olm.OlmPkEncryption
import org.matrix.olm.OlmPkMessage
import java.util.*

/**
 * A KeyBackup class instance manage incremental backup of e2e keys (megolm keys)
 * to the user's homeserver.
 */
class KeysBackup(private val mCrypto: MXCrypto, session: MXSession) {

    private val mRoomKeysRestClient = session.roomKeysRestClient

    private val mKeysBackupStateManager = KeysBackupStateManager()

    // The backup version being used.
    private var mKeyBackupVersion: KeysVersionResult? = null

    // The backup key being used.
    private var mBackupKey: OlmPkEncryption? = null

    private val mRandom = Random()

    private var backupAllGroupSessionsObserver: ApiCallback<Void?>? = null

    private var mKeysBackupStateListener: KeysBackupStateManager.KeysBackupStateListener? = null

    val isEnabled: Boolean
        get() = mKeysBackupStateManager.state !== KeysBackupStateManager.KeysBackupState.Disabled

    val state: KeysBackupStateManager.KeysBackupState
        get() = mKeysBackupStateManager.state

    fun addListener(listener: KeysBackupStateManager.KeysBackupStateListener) {
        mKeysBackupStateManager.addListener(listener)
    }

    fun removeListener(listener: KeysBackupStateManager.KeysBackupStateListener) {
        mKeysBackupStateManager.removeListener(listener)
    }

    /**
     * Set up the data required to create a new backup version.
     * The backup version will not be created and enabled until [&lt;][.createKeyBackupVersion]
     * is called.
     * The returned [MegolmBackupCreationInfo] object has a `recoveryKey` member with
     * the user-facing recovery key string.
     *
     * @param callback Asynchronous callback
     */
    fun prepareKeysBackupVersion(callback: SuccessErrorCallback<MegolmBackupCreationInfo>) {
        mCrypto.decryptingThreadHandler.post {
            try {
                val olmPkDecryption = OlmPkDecryption()

                val megolmBackupAuthData = MegolmBackupAuthData()
                megolmBackupAuthData.publicKey = olmPkDecryption.generateKey()
                megolmBackupAuthData.signatures = mCrypto.signObject(JsonUtils.getCanonicalizedJsonString(megolmBackupAuthData))

                val megolmBackupCreationInfo = MegolmBackupCreationInfo()

                megolmBackupCreationInfo.algorithm = MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
                megolmBackupCreationInfo.authData = megolmBackupAuthData
                megolmBackupCreationInfo.recoveryKey = computeRecoveryKey(olmPkDecryption.privateKey())

                mCrypto.uiHandler.post { callback.onSuccess(megolmBackupCreationInfo) }
            } catch (e: OlmException) {
                Log.e(LOG_TAG, "OlmException: ", e)

                mCrypto.uiHandler.post { callback.onUnexpectedError(e) }
            }
        }
    }

    /**
     * Create a new key backup version and enable it, using the information return from
     * [.prepareKeysBackupVersion].
     *
     * @param keyBackupCreationInfo the info object from `prepareKeyBackupVersion`.
     * @param callback              Asynchronous callback
     */
    fun createKeyBackupVersion(keyBackupCreationInfo: MegolmBackupCreationInfo,
                               callback: ApiCallback<KeysVersion>) {
        // Reset backup markers. Only on success?
        mCrypto.cryptoStore.resetBackupMarkers()

        val createKeysBackupVersionBody = CreateKeysBackupVersionBody()
        createKeysBackupVersionBody.algorithm = keyBackupCreationInfo.algorithm
        createKeysBackupVersionBody.authData = JsonUtils.getGson(false).toJsonTree(keyBackupCreationInfo.authData)

        mRoomKeysRestClient.createKeysBackupVersion(createKeysBackupVersionBody, object : SimpleApiCallback<KeysVersion>(callback) {
            override fun onSuccess(info: KeysVersion) {
                val keyBackupVersion = KeysVersionResult()
                keyBackupVersion.algorithm = createKeysBackupVersionBody.algorithm
                keyBackupVersion.authData = createKeysBackupVersionBody.authData
                keyBackupVersion.version = info.version

                enableKeyBackup(keyBackupVersion)

                callback.onSuccess(info)
            }
        })
    }

    /**
     * Delete a key backup version.
     * If we are backing up to this version. Backup will be stopped.
     *
     * @param version  the backup version to delete.
     * @param callback Asynchronous callback
     */
    fun deleteKeyBackupVersion(version: String, callback: ApiCallback<Void>) {
        mCrypto.decryptingThreadHandler.post {
            // If we're currently backing up to this backup... stop.
            // (We start using it automatically in createKeyBackupVersion
            // so this is symmetrical).
            if (mKeyBackupVersion != null && version == mKeyBackupVersion!!.version) {
                disableKeyBackup()
            }

            mRoomKeysRestClient.deleteKeysBackup(version, callback)
        }
    }

    /**
     * Start to back up keys immediately.
     *
     * @param progress the callback to follow the progress
     * @param callback the main callback
     */
    fun backupAllGroupSessions(progress: BackupProgress?,
                               callback: ApiCallback<Void?>?) {
        // Get a status right now
        getBackupProgress(object : BackupProgress {
            override fun onProgress(backedUp: Int, total: Int) {
                // Reset previous state if any
                resetBackupAllGroupSessionsObjects()
                Log.d(LOG_TAG, "backupAllGroupSessions: backupProgress: $backedUp/$total")
                progress?.onProgress(backedUp, total)

                if (backedUp == total) {
                    Log.d(LOG_TAG, "backupAllGroupSessions: complete")
                    callback?.onSuccess(null)
                    return
                }

                // Listen to `state` change to determine when to call onBackupProgress and onComplete
                backupAllGroupSessionsObserver = callback

                mKeysBackupStateListener = object : KeysBackupStateManager.KeysBackupStateListener {
                    override fun onStateChange(newState: KeysBackupStateManager.KeysBackupState) {
                        getBackupProgress(object : BackupProgress {
                            override fun onProgress(backedUp: Int, total: Int) {
                                progress?.onProgress(backedUp, total)
                                if (mKeysBackupStateManager.state === KeysBackupStateManager.KeysBackupState.ReadyToBackUp) {
                                    resetBackupAllGroupSessionsObjects()
                                    callback?.onSuccess(null)
                                }
                            }
                        })
                    }
                }

                mKeysBackupStateManager.addListener(mKeysBackupStateListener!!)
            }
        })
    }

    private fun resetBackupAllGroupSessionsObjects() {
        backupAllGroupSessionsObserver = null

        mKeysBackupStateManager.removeListener(mKeysBackupStateListener!!)
    }

    interface BackupProgress {
        fun onProgress(backedUp: Int, total: Int)
    }

    private fun getBackupProgress(listener: BackupProgress) {
        mCrypto.decryptingThreadHandler.post {
            val total = mCrypto.cryptoStore.inboundGroupSessionsCount(false)
            val backedUpKeys = mCrypto.cryptoStore.inboundGroupSessionsCount(true)

            mCrypto.uiHandler.post { listener.onProgress(backedUpKeys, total) }
        }
    }

    /**
     * Restore a backup from a given backup version stored on the homeserver.
     *
     * @param version     the backup version to restore from.
     * @param recoveryKey the recovery key to decrypt the retrieved backup.
     * @param roomId      the id of the room to get backup data from.
     * @param sessionId   the id of the session to restore.
     * @param callback    Callback. Itprovides the number of found keys and the number of successfully imported keys.
     */
    fun restoreKeyBackup(version: String,
                         recoveryKey: String,
                         roomId: String?,
                         sessionId: String?,
                         callback: ApiCallback<ImportRoomKeysResult>?) {
        Log.d(LOG_TAG, "restoreKeyBackup: From backup version: $version")

        mCrypto.decryptingThreadHandler.post(Runnable {
            // Get a PK decryption instance
            val decryption = pkDecryptionFromRecoveryKey(recoveryKey)
            if (decryption == null) {
                Log.e(LOG_TAG, "restoreKeyBackup: Invalid recovery key. Error")
                if (callback != null) {
                    mCrypto.uiHandler.post { callback.onUnexpectedError(Exception("Invalid recovery key")) }
                }
                return@Runnable
            }

            // Get backup from the homeserver
            keyBackupForSession(sessionId, roomId, version, object : ApiCallback<KeysBackupData> {
                override fun onUnexpectedError(e: Exception) {
                    if (callback != null) {
                        mCrypto.uiHandler.post { callback.onUnexpectedError(e) }
                    }
                }

                override fun onNetworkError(e: Exception) {
                    if (callback != null) {
                        mCrypto.uiHandler.post { callback.onNetworkError(e) }
                    }
                }

                override fun onMatrixError(e: MatrixError) {
                    if (callback != null) {
                        mCrypto.uiHandler.post { callback.onMatrixError(e) }
                    }
                }

                override fun onSuccess(keysBackupData: KeysBackupData) {
                    val sessionsData = ArrayList<MegolmSessionData>()
                    // Restore that data
                    for (roomIdLoop in keysBackupData.roomIdToRoomKeysBackupData.keys) {
                        for (sessionIdLoop in keysBackupData.roomIdToRoomKeysBackupData[roomIdLoop]!!.sessionIdToKeyBackupData.keys) {
                            val keyBackupData = keysBackupData.roomIdToRoomKeysBackupData[roomIdLoop]!!.sessionIdToKeyBackupData[sessionIdLoop]!!

                            val sessionData = decryptKeyBackupData(keyBackupData, sessionIdLoop, roomIdLoop, decryption)

                            sessionData?.let {
                                sessionsData.add(it)
                            }
                        }
                    }
                    Log.d(LOG_TAG, "restoreKeyBackup: Got " + sessionsData.size + " keys from the backup store on the homeserver")
                    // Do not trigger a backup for them if they come from the backup version we are using
                    val backUp = version != mKeyBackupVersion!!.version
                    if (backUp) {
                        Log.d(LOG_TAG, "restoreKeyBackup: Those keys will be backed up to backup version: " + mKeyBackupVersion!!.version)
                    }

                    // Import them into the crypto store
                    mCrypto.importMegolmSessionsData(sessionsData, backUp, callback)
                }
            })
        })
    }

    /**
     * Same method as [RoomKeysRestClient.getRoomKeyBackup] except that it accepts nullable
     * parameters and always returns a KeysBackupData object
     */
    private fun keyBackupForSession(sessionId: String?,
                                    roomId: String?,
                                    version: String,
                                    callback: ApiCallback<KeysBackupData>) {
        if (roomId != null && sessionId != null) {
            // Get key for the room and for the session
            mRoomKeysRestClient.getRoomKeyBackup(roomId, sessionId, version, object : SimpleApiCallback<KeyBackupData>(callback) {
                override fun onSuccess(info: KeyBackupData) {
                    // Convert to KeysBackupData
                    val keysBackupData = KeysBackupData()
                    keysBackupData.roomIdToRoomKeysBackupData = HashMap()
                    val roomKeysBackupData = RoomKeysBackupData()
                    roomKeysBackupData.sessionIdToKeyBackupData = HashMap()
                    roomKeysBackupData.sessionIdToKeyBackupData[sessionId] = info
                    keysBackupData.roomIdToRoomKeysBackupData[roomId] = roomKeysBackupData

                    callback.onSuccess(keysBackupData)
                }
            })
        } else if (roomId != null) {
            // Get all keys for the room
            mRoomKeysRestClient.getRoomKeysBackup(roomId, version, object : SimpleApiCallback<RoomKeysBackupData>(callback) {
                override fun onSuccess(info: RoomKeysBackupData) {
                    // Convert to KeysBackupData
                    val keysBackupData = KeysBackupData()
                    keysBackupData.roomIdToRoomKeysBackupData = HashMap()
                    keysBackupData.roomIdToRoomKeysBackupData[roomId] = info

                    callback.onSuccess(keysBackupData)
                }
            })
        } else {
            // Get all keys
            mRoomKeysRestClient.getKeysBackup(version, callback)
        }
    }

    @VisibleForTesting
    fun pkDecryptionFromRecoveryKey(recoveryKey: String): OlmPkDecryption? {
        // Extract the primary key
        val privateKey = extractCurveKeyFromRecoveryKey(recoveryKey)

        // Built the PK decryption with it
        var decryption: OlmPkDecryption? = null
        if (privateKey != null) {
            try {
                decryption = OlmPkDecryption()
                decryption.setPrivateKey(privateKey)
            } catch (e: OlmException) {
                Log.e(LOG_TAG, "OlmException", e)
            }

        }

        return decryption
    }

    fun maybeSendKeyBackup() {
        if (mKeysBackupStateManager.state === KeysBackupStateManager.KeysBackupState.ReadyToBackUp) {
            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.WillBackUp

            // Wait between 0 and 10 seconds, to avoid backup requests from
            // different clients hitting the server all at the same time when a
            // new key is sent
            val delayInMs = mRandom.nextInt(KEY_BACKUP_WAITING_TIME_TO_SEND_KEY_BACKUP_MILLIS)

            mCrypto.decryptingThreadHandler.postDelayed({ sendKeyBackup() }, delayInMs.toLong())
        } else {
            Log.d(LOG_TAG, "maybeSendKeyBackup: Skip it because state: " + mKeysBackupStateManager.state)
        }
    }


    /* ==========================================================================================
     * Private
     * ========================================================================================== */

    /**
     * Retrieve the current version of the backup from the home server
     *
     * @param callback
     */
    private fun getCurrentVersion(callback: ApiCallback<KeysVersionResult>) {
        mRoomKeysRestClient.getKeysBackupVersion(null, callback)
    }

    /**
     * Enable backing up of keys.
     *
     * @param keysVersionResult backup information object as returned by [.getCurrentVersion].
     * @return true in case of success, else false
     */
    private fun enableKeyBackup(keysVersionResult: KeysVersionResult?): Boolean {
        if (keysVersionResult?.authData != null) {
            val retrievedMegolmBackupAuthData = JsonUtils.getGson(false)
                    .fromJson(keysVersionResult.authData, MegolmBackupAuthData::class.java)

            if (retrievedMegolmBackupAuthData != null) {
                mKeyBackupVersion = keysVersionResult

                try {
                    mBackupKey = OlmPkEncryption()
                    mBackupKey!!.setRecipientKey(retrievedMegolmBackupAuthData.publicKey)
                } catch (e: OlmException) {
                    Log.e(LOG_TAG, "OlmException", e)
                    return false
                }

                mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.ReadyToBackUp

                maybeSendKeyBackup()

                return true
            } else {
                Log.e(LOG_TAG, "Invalid authentication data")
                return false
            }
        } else {
            Log.e(LOG_TAG, "Invalid authentication data")
            return false
        }
    }

    /**
     * Disable backing up of keys.
     */
    private fun disableKeyBackup() {
        resetBackupAllGroupSessionsObjects()

        mKeyBackupVersion = null
        mBackupKey = null
        mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.Disabled

        // Reset backup markers
        mCrypto.cryptoStore.resetBackupMarkers()
    }

    private fun sendKeyBackup() {
        // Get a chunk of keys to backup
        val sessions = mCrypto.cryptoStore.inboundGroupSessionsToBackup(KEY_BACKUP_SEND_KEYS_MAX_COUNT)

        Log.d(LOG_TAG, "sendKeyBackup: " + sessions.size + " sessions to back up")

        if (sessions.isEmpty()) {
            // Backup is up to date
            mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.ReadyToBackUp
            return
        }

        val currentState = mKeysBackupStateManager.state

        if (currentState === KeysBackupStateManager.KeysBackupState.BackingUp || currentState === KeysBackupStateManager.KeysBackupState.Disabled) {
            // Do nothing if we are already backing up or if the backup has been disabled
            Log.d(LOG_TAG, "sendKeyBackup: Invalid state: $currentState")
            return
        }

        // Sanity check
        if (mBackupKey == null || mKeyBackupVersion == null) {
            return
        }

        mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.BackingUp

        // Gather data to send to the homeserver
        // roomId -> sessionId -> MXKeyBackupData
        val keysBackupData = KeysBackupData()
        keysBackupData.roomIdToRoomKeysBackupData = HashMap()

        for (session in sessions) {
            val keyBackupData = encryptGroupSession(session)
            if (keysBackupData.roomIdToRoomKeysBackupData[session.mRoomId] == null) {
                val roomKeysBackupData = RoomKeysBackupData()
                roomKeysBackupData.sessionIdToKeyBackupData = HashMap()
                keysBackupData.roomIdToRoomKeysBackupData[session.mRoomId] = roomKeysBackupData
            }

            try {
                keysBackupData.roomIdToRoomKeysBackupData[session.mRoomId]!!.sessionIdToKeyBackupData[session.mSession.sessionIdentifier()] = keyBackupData
            } catch (e: OlmException) {
                Log.e(LOG_TAG, "OlmException", e)
            }

        }

        // Make the request
        mRoomKeysRestClient.sendKeysBackup(mKeyBackupVersion!!.version!!, keysBackupData, object : ApiCallback<Void> {
            override fun onNetworkError(e: Exception) {
                if (backupAllGroupSessionsObserver != null) {
                    backupAllGroupSessionsObserver!!.onNetworkError(e)
                }

                onError()
            }

            private fun onError() {
                // TODO: Manage retries
                Log.e(LOG_TAG, "sendKeyBackup: sendKeysBackup failed.")
            }

            override fun onMatrixError(e: MatrixError) {
                if (backupAllGroupSessionsObserver != null) {
                    backupAllGroupSessionsObserver!!.onMatrixError(e)
                }

                onError()
            }

            override fun onUnexpectedError(e: Exception) {
                if (backupAllGroupSessionsObserver != null) {
                    backupAllGroupSessionsObserver!!.onUnexpectedError(e)
                }

                onError()
            }

            override fun onSuccess(info: Void) {
                // Mark keys as backed up
                for (session in sessions) {
                    try {
                        mCrypto.cryptoStore.markBackupDoneForInboundGroupSessionWithId(session.mSession.sessionIdentifier(), session.mSenderKey)
                    } catch (e: OlmException) {
                        Log.e(LOG_TAG, "OlmException", e)
                    }

                }

                if (sessions.size < KEY_BACKUP_SEND_KEYS_MAX_COUNT) {
                    Log.d(LOG_TAG, "sendKeyBackup: All keys have been backed up")
                    mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.ReadyToBackUp
                } else {
                    Log.d(LOG_TAG, "sendKeyBackup: Continue to back up keys")
                    mKeysBackupStateManager.state = KeysBackupStateManager.KeysBackupState.WillBackUp

                    sendKeyBackup()
                }
            }
        })
    }

    @VisibleForTesting
    fun encryptGroupSession(session: MXOlmInboundGroupSession2): KeyBackupData {
        // Gather information for each key
        // TODO: userId?
        val device = mCrypto.deviceWithIdentityKey(session.mSenderKey, null, MXCRYPTO_ALGORITHM_MEGOLM)

        // Build the m.megolm_backup.v1.curve25519-aes-sha2 data as defined at
        // https://github.com/uhoreg/matrix-doc/blob/e2e_backup/proposals/1219-storing-megolm-keys-serverside.md#mmegolm_backupv1curve25519-aes-sha2-key-format
        val sessionData = session.exportKeys()
        val sessionBackupData = HashMap<String, Any>()
        sessionBackupData["algorithm"] = sessionData!!.algorithm
        sessionBackupData["sender_key"] = sessionData.sender_key
        sessionBackupData["sender_claimed_keys"] = sessionData.sender_claimed_keys
        sessionBackupData["forwarding_curve25519_key_chain"] = if (sessionData.forwardingCurve25519KeyChain == null) ArrayList<Any>() else sessionData.forwardingCurve25519KeyChain
        sessionBackupData["session_key"] = sessionData.session_key

        var encryptedSessionBackupData: OlmPkMessage? = null
        try {
            encryptedSessionBackupData = mBackupKey!!.encrypt(JsonUtils.getGson(false).toJson(sessionBackupData))
        } catch (e: OlmException) {
            Log.e(LOG_TAG, "OlmException", e)
        }

        // Build backup data for that key
        val keyBackupData = KeyBackupData()
        try {
            keyBackupData.firstMessageIndex = session.mSession.firstKnownIndex
        } catch (e: OlmException) {
            Log.e(LOG_TAG, "OlmException", e)
        }

        keyBackupData.forwardedCount = session.mForwardingCurve25519KeyChain.size
        keyBackupData.isVerified = device!!.isVerified

        val data = HashMap<String, Any>()
        data["ciphertext"] = encryptedSessionBackupData!!.mCipherText
        data["mac"] = encryptedSessionBackupData.mMac
        data["ephemeral"] = encryptedSessionBackupData.mEphemeralKey

        keyBackupData.sessionData = JsonUtils.getGson(false).toJsonTree(data)

        return keyBackupData
    }

    @VisibleForTesting
    fun decryptKeyBackupData(keyBackupData: KeyBackupData, sessionId: String, roomId: String, decryption: OlmPkDecryption?): MegolmSessionData? {
        var sessionBackupData: MegolmSessionData? = null

        val jsonObject = keyBackupData.sessionData?.asJsonObject

        val ciphertext = jsonObject?.get("ciphertext")?.asString
        val mac = jsonObject?.get("mac")?.asString
        val ephemeralKey = jsonObject?.get("ephemeral")?.asString

        if (ciphertext != null && mac != null && ephemeralKey != null) {
            val encrypted = OlmPkMessage()
            encrypted.mCipherText = ciphertext
            encrypted.mMac = mac
            encrypted.mEphemeralKey = ephemeralKey

            try {
                val decrypted = decryption!!.decrypt(encrypted)
                sessionBackupData = JsonUtils.toClass(decrypted, MegolmSessionData::class.java)
            } catch (e: OlmException) {
                Log.e(LOG_TAG, "OlmException", e)
            }

            if (sessionBackupData != null) {
                sessionBackupData.session_id = sessionId
                sessionBackupData.room_id = roomId
            }
        }

        return sessionBackupData
    }

    companion object {
        private val LOG_TAG = KeysBackup::class.java.simpleName

        // Maximum delay in ms in {@link maybeSendKeyBackup}
        private const val KEY_BACKUP_WAITING_TIME_TO_SEND_KEY_BACKUP_MILLIS = 10000

        // Maximum number of keys to send at a time to the homeserver.
        private const val KEY_BACKUP_SEND_KEYS_MAX_COUNT = 100
    }
}