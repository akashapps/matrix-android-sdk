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

import java.util.*

class KeysBackupStateManager {

    private val mListeners = ArrayList<KeysBackupStateListener>()

    // Backup state
    var state = KeysBackupState.Disabled
        set(newState) {
            field = newState

            // Notify listerners about the state change
            synchronized(mListeners) {
                for (listener in mListeners) {
                    listener.onStateChange(state)
                }
            }
        }

    /**
     * E2e keys backup states.
     *
     * <pre>
     *                               |
     *                               V        deleteKeyBackupVersion (on current backup)
     *  +---------------------->  UNKNOWN  <-------------
     *  |                            |
     *  |                            | checkAndStartKeyBackup
     *  |                            V
     *  |                     CHECKING BACKUP
     *  |                            |
     *  |  Network error             |
     *  +<---------------------------+-------------> DISABLED <----------+
     *  |                            |                  |                |
     *  |                            |                  V                |
     *  |                            |               ENABLING            |
     *  |                            V                  |          error |
     *  |                  +--->   READY   <------------+----------------+
     *  |                  |         |
     *  |                  |         | on new key
     *  |                  |         V
     *  |                  |    WILL BACK UP
     *  |                  |         |
     *  |                  |         V
     *  |                  |     BACKING UP
     *  | Error            |         |
     *  +<-----------------+---------+
    </pre> *
     */
    enum class KeysBackupState {
        // Backup is not enabled
        Disabled,
        // Backup is being enabled
        Enabling,
        // Backup is enabled and ready to send backup to the homeserver
        ReadyToBackUp,
        // Backup is going to be send to the homeserver
        WillBackUp,
        // Backup is being sent to the homeserver
        BackingUp
    }

    interface KeysBackupStateListener {
        fun onStateChange(newState: KeysBackupState)
    }

    fun addListener(listener: KeysBackupStateListener) {
        synchronized(mListeners) {
            mListeners.add(listener)
        }
    }

    fun removeListener(listener: KeysBackupStateListener) {
        synchronized(mListeners) {
            mListeners.remove(listener)
        }
    }
}