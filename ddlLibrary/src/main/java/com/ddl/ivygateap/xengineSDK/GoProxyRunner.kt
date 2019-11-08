package com.ddl.ivygateap.xengineSDK

import android.util.Log
import com.ddl.ivygateap.secondarydevelopment.DDLConf
import snail007.proxysdk.LogCallback
import snail007.proxysdk.Proxysdk
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

internal object GoProxyRunner {
    private val TAG = GoProxyRunner::class.java.simpleName
    private val serviceID = "http01" // unique string
    private var server: ServerSocketChannel? = null
    var servicePort = 0
        private set


    private fun getServicePort() {
        server = ServerSocketChannel.open()
        server!!.socket().reuseAddress = true
        server!!.socket().bind(InetSocketAddress(servicePort))
        servicePort = server!!.socket().localPort
    }

    private fun closeSocket() {
        server!!.close()
    }

    fun startProxy() {
        try {
            var tries = 0
            while (!tryStart()) {
                tries++
                if (tries > 5) {
                    throw Exception("GoProxy failed to start, stopped")
                }
                Thread.sleep(50L shl tries)
            }
        } catch (e: Exception) {
            Log.e(TAG, e.toString(), e)
        }
    }

    private fun tryStart(): Boolean {
        getServicePort()

        if (servicePort == 0) {
            Log.wtf(TAG, "no port available!")
            closeSocket()
            return false
        }

        val serviceArgs = "sps -S http -T tcp -P 127.0.0.1:${SocketServer.servicePort} -A ${DDLConf.username}:${DDLConf.password} -t tcp -p 127.0.0.1:${this.servicePort}"
        val serviceAdd = ""

        closeSocket()
        val errorString = Proxysdk.startWithLog(serviceID, serviceArgs, serviceAdd, GoProxyLogger()) // TODO: may still fail

        if (errorString.isEmpty()) {
            // succeeded
            Log.i(TAG, "started")
            return true
        } else {
            // failed
            Log.e(TAG, "start fail, error: $errorString")
            return false
        }
    }

    fun stopPorxy() {
        Proxysdk.stop(serviceID)
    }

    private class GoProxyLogger : LogCallback {
        override fun write(p0: String) {
            Log.i(TAG, p0)
        }
    }
}
