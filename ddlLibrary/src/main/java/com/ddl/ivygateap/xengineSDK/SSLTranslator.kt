package com.ddl.ivygateap.xengineSDK

import android.util.Log
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLEngineResult.HandshakeStatus

class SSLTranslator {
    companion object {
        private val TAG = SSLTranslator::class.java.simpleName
    }

    private lateinit var engineSSL: SSLEngine
    private var appBufferMax = 0
    private var netBufferMax = 0
    private lateinit var clientAppData: ByteBuffer
    private lateinit var clientNetData: ByteBuffer
    private lateinit var peerAppData: ByteBuffer
    private lateinit var peerNetData: ByteBuffer

    fun initSSL(protocol: String) {
        val contextSSL = SSLContext.getInstance(protocol)
        contextSSL.init(null, null, null)
        engineSSL = contextSSL.createSSLEngine(SocketServer.uri.host, SocketServer.uri.port)
//        engineSSL.sslParameters.endpointIdentificationAlgorithm = "HTTPS"
        engineSSL.useClientMode = true

        // TODO: verify the session?
        val peerSSLSession = engineSSL.session

        // TODO: is the buffersize ideal?
        appBufferMax = peerSSLSession.applicationBufferSize
        netBufferMax = peerSSLSession.packetBufferSize
        Log.d(TAG, "SSLSession: applicationBufferSize $appBufferMax")
        Log.d(TAG, "SSLSession: packetBufferSize $netBufferMax")

        // Create byte buffers to use for holding application data
        clientAppData = ByteBuffer.allocateDirect(appBufferMax)
        clientAppData.position(appBufferMax)
        clientNetData = ByteBuffer.allocateDirect(netBufferMax)
        peerAppData = ByteBuffer.allocateDirect(appBufferMax)
        peerNetData = ByteBuffer.allocateDirect(netBufferMax)


        // ready to use (handshake needed)
        Log.i(TAG, "SSL init finished, protocol: $protocol")
    }

    @Throws(Exception::class)
    fun runSSLHandshake(socketChannel: SocketChannel) {
        // Begin handshake
        engineSSL.beginHandshake()
        var result = engineSSL.handshakeStatus

        // Process handshaking message
        while (result != HandshakeStatus.FINISHED
            && result != HandshakeStatus.NOT_HANDSHAKING) {
//            Log.v(TAG, "$result")
            when (result) {

                HandshakeStatus.NEED_UNWRAP -> {
                    // Receive handshaking data from peer
                    if (socketChannel.read(peerNetData) < 0) {
                        // The channel has reached end-of-stream
                        Log.e(TAG, "handshake: end of stream")
                    }

                    // Process incoming handshaking data
                    peerNetData.flip()
                    val res = engineSSL.unwrap(peerNetData, peerAppData)
                    peerNetData.compact()
                    result = res.handshakeStatus
//                    Log.v(TAG, "${res.status}")

                    // Check status
                    when (res.status) {
                        Status.OK -> {
                        }
                    }// Handle OK status
                    // Handle other status: BUFFER_UNDERFLOW, BUFFER_OVERFLOW, CLOSED
                }

                HandshakeStatus.NEED_WRAP -> {
                    // Empty the local network packet buffer.
                    clientNetData.clear()

                    // Generate handshaking data
                    val res = engineSSL.wrap(clientAppData, clientNetData)
                    result = res.getHandshakeStatus()
//                    Log.v(TAG, "${res.status}")

                    // Check status
                    when (res.status) {
                        Status.OK -> {
                            clientNetData.flip()

                            // Send the handshaking data to peer
                            while (clientNetData.hasRemaining()) {
                                socketChannel.write(clientNetData)
                            }
                        }
                    }// Handle other status:  BUFFER_OVERFLOW, BUFFER_UNDERFLOW, CLOSED
                }

                HandshakeStatus.NEED_TASK -> {
                    // Handle blocking tasks
                    var task: Runnable? = engineSSL.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engineSSL.delegatedTask
                    }
                    result = engineSSL.handshakeStatus
                }
            }// Handle other status:  // FINISHED or NOT_HANDSHAKING
        }

        //cleaning
        clientAppData.clear()
        clientNetData.clear()
        peerAppData.clear()
        peerNetData.clear()

        // Processes after handshaking
        Log.d(TAG, "done handshaking: $result")
    }

    /*
    * If the result indicates that we have outstanding tasks to do,
    * go ahead and run them in this thread.
    */
    @Throws(Exception::class)
    private fun runDelegatedTasks(
        result: SSLEngineResult,
        engine: SSLEngine
    ) {
        if (result.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = engine.delegatedTask
            while (runnable != null) {
                Log.d(TAG, "running delegated task...")
                runnable.run()
                runnable = engine.delegatedTask
            }
            val hsStatus = engine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception(
                    "handshake shouldn't need additional tasks"
                )
            }
            Log.d(TAG, "new HandshakeStatus: $hsStatus")
        }
    }

    // inbound side
    // (buffer => peerNetData) -> peerAppData
    // unwarp a ByteBuffer into the cache list
    fun read(buffer: ByteBuffer, list: LinkedList<ByteBuffer>) {
        // put netData from buffer
        peerNetData.put(buffer)
        peerNetData.flip()

        // clear appData
        peerAppData.clear()

        // translate to appData
        val result = engineSSL.unwrap(peerNetData, peerAppData)
        Log.d(TAG, "unwrap: $result")

        if (result.status == SSLEngineResult.Status.OK) {
            peerAppData.flip()
            while (peerAppData.hasRemaining()) {
                val newBuffer = ByteBufferPool.acquire().put(peerAppData)
                newBuffer.flip()
                list.add(newBuffer)

                // clear netData with compact
                // because you may have read next TLS package header
                if (peerNetData.hasRemaining()) {
                    Log.d(TAG, "has remaining peerNetData")
                }
                peerNetData.compact()
            }
        } else if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            // TODO
            Log.w(TAG, "overflow TODO")
        } else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            // compact netData
            peerNetData.compact()
            Log.d(TAG, "underflow: compact netData")
        }
    }

    // outbound side
    // (buffer => clientAppData) -> clientNetData
    // warp a ByteBuffer into the channel
    fun write(buffer: ByteBuffer, channel: SocketChannel): Boolean {
        // put appData from buffer
        clientAppData.put(buffer)
        clientAppData.flip()

        // clear netData
        clientNetData.clear()

        // translate to netData
        val result = engineSSL.wrap(clientAppData, clientNetData)
        Log.d(TAG, "wrap: $result")

        if (result.status == SSLEngineResult.Status.OK) {
            clientNetData.flip()
            channel.write(clientNetData)
        } else if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            // TODO
            Log.w(TAG, "overflow TODO")
        } else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            // TODO
            Log.wtf(TAG, "Why underflow in wrap ?")
        }

        // clear appData
        clientAppData.clear()

        // returns the original buffer status
        // to decide if it will be removed from the cache list
        return buffer.hasRemaining()
    }

    fun shutdwon() {
        engineSSL.closeInbound()
        engineSSL.closeOutbound()
    }
}
