package com.ddl.ivygateap.xengineSDK

import android.util.Log
import com.ddl.ivygateap.secondarydevelopment.DDLConf
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.channels.ServerSocketChannel
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class SocketServer : Runnable {
    companion object {
        private val TAG = SocketServer::class.java.simpleName
        //val uri = URI("https://test.edu.automesh.org:29980")
        //val uri = URI("http://test.light.ustclug.org:29979")
        val uri = URI(DDLConf.socketServer)
        var servicePort = 0
            private set
    }

    private val executorService: ExecutorService = Executors.newFixedThreadPool(100)
    private val threadList: MutableList<Future<*>> = LinkedList() // TODO: map and manage it!
    private var server = ServerSocketChannel.open()
    private var useSSL = false

    init {
        server.socket().reuseAddress = true
        server.socket().bind(InetSocketAddress(servicePort))
        servicePort = server.socket().localPort
        server.configureBlocking(false)
        Log.d(TAG, "listening at $servicePort, forwarding to ${uri}")

        if (uri.scheme == "https") {
            useSSL = true
            Log.d(TAG, "useSSL: use EchoThreadSSL")
        }
    }

    fun close() {
        server.close()
    }

    override fun run() {
        Log.i(TAG, "working ...")
        try {
            while (!Thread.interrupted() && server.isOpen) {
                val clientChannel = server.accept()
                if (clientChannel != null) {
//                    clientChannel.socket().reuseAddress = true
                    if (useSSL) {
                        threadList.add(executorService.submit(EchoThreadSSL(clientChannel)))
                    } else {
                        threadList.add(executorService.submit(EchoThread(clientChannel)))
                    }
                    Log.d(TAG, "new EchoThread ${threadList.last()}, from ${clientChannel.remoteAddress}")
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "interrupted")
        } catch (e: IOException) {
            Log.w(TAG, e.toString(), e)
        } finally {
            server.close()
            threadList.forEach { t ->
                t.cancel(true)
                Log.i(TAG, "canceled ${t}")}
            Log.i(TAG, "stopped")
        }
    }
}
