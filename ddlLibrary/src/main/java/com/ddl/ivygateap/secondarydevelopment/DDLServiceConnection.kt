package com.ddl.ivygateap.secondarydevelopment

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.ddl.ivygateap.IRemoteCallback
import com.ddl.ivygateap.IRemoteService

/**
 * @Package:        com.ddl.ivygateap.secondarydevelopment
 * @ClassName:      DDLServiceConnection
 * @Description:
 * @Author:          Kevin
 * @CreateDate:     2019-11-03 19:43
 */
class DDLServiceConnection(val listener: DDLVpnStateChangeListener) : ServiceConnection {
    private var iRemoteService: IRemoteService? = null
    private var iRemoteCallback: Callback? = null
    private var mListener: DDLVpnStateChangeListener? = listener

    override fun onServiceDisconnected(p0: ComponentName?) {
        mListener?.ddlServiceDisconnected()
        iRemoteService?.unRegister(iRemoteCallback)
        iRemoteService = null
        iRemoteCallback = null
        mListener = null
    }

    override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {
        mListener?.ddlServiceConnected()
        iRemoteService = IRemoteService.Stub.asInterface(p1)
        iRemoteCallback = Callback()
        iRemoteService?.register(iRemoteCallback)
        iRemoteService?.startVpn()
    }

    inner class Callback : IRemoteCallback.Stub() {
        override fun onStateChanged(state: String?) {
            mListener?.ddlVpnStateChanged(vpnStateSwitch(state))
        }

    }

    fun getVpnState(): DDLVpnState {
        return vpnStateSwitch(iRemoteService?.vpnState)
    }

    fun isVpnRunning():Boolean {
        return iRemoteService?.isVpnRunning ?: false
    }

    fun stopVpn() {
        iRemoteService?.stopVpn()
    }

    private fun vpnStateSwitch(state:String?):DDLVpnState {
        return when (state) {
            "starting" -> DDLVpnState.STARTING
            "started" -> DDLVpnState.STARTED
            "stopping" -> DDLVpnState.STOPPING
            "stopped" -> DDLVpnState.STOPPED
            else -> DDLVpnState.STOPPED
        }
    }

}