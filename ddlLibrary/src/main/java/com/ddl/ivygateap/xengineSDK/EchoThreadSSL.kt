package com.ddl.ivygateap.xengineSDK

import com.ddl.ivygateap.xengineSDK.localDNS.IPPool
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
//import kotlinx.coroutines.*
import java.net.SocketException

internal class EchoThreadSSL(private val clientChannel: SocketChannel) : Runnable {
    companion object {
        private val TAG = EchoThreadSSL::class.java.simpleName
//        private const val READ_TIMEOUT = 2000
    }

    private val bufferListForward = ConcurrentLinkedQueue<ByteBuffer>()
    private val bufferListBackward = ConcurrentLinkedQueue<ByteBuffer>()
    private lateinit var peerSocketSSL: SSLSocket
    private var connected = false
    //private val scope = CoroutineScope(newFixedThreadPoolContext(100, "EchoSSL"))
    private var clientReadEnd = false
    //private lateinit var peerRead: Job
    private lateinit var peerRead: PeerReadThread

    init {
        clientChannel.configureBlocking(false)
    }

    override fun run() {
        createSocket()
        runTCPForwarding()
    }

    private fun createSocket() {
        val contextSSL = SSLContext.getInstance("TLSv1.2")
        contextSSL.init(null, null, SecureRandom())

        val factory = contextSSL.socketFactory
        peerSocketSSL =
            factory.createSocket(SocketServer.uri.host, SocketServer.uri.port) as SSLSocket
        peerSocketSSL.useClientMode = true
//        peerSocketSSL.soTimeout = READ_TIMEOUT
        peerSocketSSL.startHandshake()

        Log.i(TAG, "peerSocketSSL connected")
    }

    private fun replaceFakeIP(buffer: ByteBuffer): ByteBuffer {
        // get Hostname
        val stream =
            String(buffer.array(), Charsets.UTF_8).substring(
                buffer.arrayOffset(),
                buffer.arrayOffset() + buffer.limit()
            )
        if (stream.substring(0, 7) == "CONNECT") {
            val headers = stream.split("\r\n")
            val uri = headers.first().split(" ")[1].split(":")
            val ipString = uri.first()
            val portString = uri.last()

            val ipArray = ByteArray(4)
            ipString.split('.').forEachIndexed { index, s -> ipArray[index] = s.toInt().toByte() }
            val hostString = IPPool.getHost(ipArray)
            if (hostString == "") {
                Log.e(TAG, "$ipString: no such host!")
            } else {
                Log.i(TAG, "CONNECT to $uri -> $hostString : $portString")
            }

            val bufferNew = ByteBufferPool.acquire()
            bufferNew.put("CONNECT $hostString:$portString HTTP/1.1\r\n".toByteArray())
            bufferNew.put("Host: $hostString:$portString\r\n".toByteArray())
            bufferNew.put("${headers[4]}\r\n".toByteArray()) // keep alive line
            bufferNew.put("${headers[5]}\r\n\r\n".toByteArray()) // basic auth line

//            Log.v(
//                TAG, String(bufferNew.array(), Charsets.UTF_8).substring(
//                    bufferNew.arrayOffset(),
//                    bufferNew.arrayOffset() + bufferNew.position()
//                )
//            )

            ByteBufferPool.release(buffer)
            return bufferNew.flip() as ByteBuffer
        } else {
            Log.w(TAG, "not a connect request")
            return buffer
        }
    }

    private fun runTCPForwarding() {
        val clientSelector = Selector.open()
        val clientKey =
            clientChannel.register(clientSelector, SelectionKey.OP_READ) // only read here

//        peerRead = scope.launch {
//            Log.d(TAG, "peerRead job init")
//        }
        peerRead = PeerReadThread(peerSocketSSL, bufferListBackward, clientSelector)
//        peerRead.priority = 1
        var peerReadStarted = false

        try {
            var i = 0
            while (!Thread.interrupted() && clientChannel.isOpen && !peerSocketSSL.isClosed) {
                Log.d(TAG, "cycle $i")
                Log.d(TAG, "F: list size: ${bufferListForward.size}")
                Log.d(TAG, "B: list size: ${bufferListBackward.size}")
                // if peer side is not closed
                if (peerSocketSSL.isClosed) {
                    clientChannel.close()
                    break
                }

                var peerWritten = false
                while (!bufferListForward.isEmpty()) {
                    // try write forward, all at once
                    val buffer = bufferListForward.poll()
                    if (!peerSocketSSL.isOutputShutdown && buffer != null) {
                        peerSocketSSL.outputStream.write(
                            buffer.array().copyOfRange(
                                buffer.arrayOffset() + buffer.position(), // will position not be 0 ?
                                buffer.arrayOffset() + buffer.limit()
                            )
                        )
//
//                        Log.v(
//                            TAG,
//                            "F: ${String(buffer.array(), Charsets.UTF_8).substring(
//                                buffer.arrayOffset(),
//                                buffer.arrayOffset() + buffer.position()
//                            )}"
//                        )
                        peerWritten = true
                        Log.d(TAG, "F: written")
                        // release element
                        ByteBufferPool.release(buffer)
                    }
                }
                if (peerWritten) {
                    // flush it !
                    Log.d(TAG, "F: flush")
                    peerSocketSSL.outputStream.flush()
                }

                // if client side is not closed
                if (!clientChannel.isOpen) {
                    peerSocketSSL.close()
                    break
                }

                // process client side
                var keySelect = 0
                if (!bufferListBackward.isEmpty()) {
                    // if have sth. to write
                    // in case the channel is almost always writable
                    keySelect = SelectionKey.OP_WRITE
                    Log.d(TAG, "client register write")
                }
                if (!clientReadEnd) {
                    keySelect = keySelect or SelectionKey.OP_READ
                    Log.d(TAG, "client register read")
                }
                Log.d(TAG, "client keySelect: $keySelect")

                // need select
                var noClientOps = false
                if (keySelect > 0) {
                    clientKey.interestOps(keySelect)

                    if (clientSelector.select() == 0) { // blocking here
                        if (bufferListBackward.isEmpty()) { // no read at peer side
                            noClientOps = true
                            Log.i(TAG, "no client ops")
                        }
                        Log.d(TAG, "client selector waked up")
                    } else {
                        val iterForward = clientSelector.selectedKeys().iterator()
                        while (iterForward.hasNext() && !Thread.interrupted()) {
                            val key = iterForward.next()
                            if (key.isValid) {
                                if (key.isReadable) { // process forward read
                                    var buffer = ByteBufferPool.acquire()
                                    var readBytes = clientChannel.read(buffer)

                                    if (readBytes < 0) {
                                        Log.i(TAG, "F: read ended")
                                        clientReadEnd = true
                                    } else if (readBytes == 0) {
                                        Log.d(TAG, "F: read 0 byte")
                                    }

                                    while (readBytes > 0) { // be careful for non-blocked channel with 0 readBytes
                                        Log.d(TAG, "F: read $readBytes bytes")
                                        buffer.flip()
                                        if (connected) {
                                            bufferListForward.offer(buffer)
                                        } else {
                                            bufferListForward.offer(replaceFakeIP(buffer))
                                            connected = true
                                        }
                                        buffer = ByteBufferPool.acquire()
                                        readBytes = clientChannel.read(buffer)
                                    }

                                    // release last empty buffer
                                    ByteBufferPool.release(buffer)
                                } else if (!bufferListBackward.isEmpty() && key.isWritable) { // process backward write
                                    val buffer = bufferListBackward.poll()
                                    if (buffer != null) {
                                        clientChannel.write(buffer)

//                                        Log.v(
//                                            TAG,
//                                            "B: ${String(buffer.array(), Charsets.UTF_8).substring(
//                                                buffer.arrayOffset(),
//                                                buffer.arrayOffset() + buffer.position()
//                                            )}"
//                                        )
                                        if (buffer.hasRemaining()) {
                                            Log.wtf(TAG, "B: why remaining ?")
                                            break
                                        } else {
                                            Log.d(TAG, "B: written")
                                            // release element
                                            ByteBufferPool.release(buffer)
                                        }
                                    }
                                }
                            }
                            iterForward.remove()
                        }
                    }
                } else {
                    Log.d(TAG, "keySelect == 0, join")
//                    runBlocking {
                    peerRead.join()
//                    }
                    if (peerRead.readError) {
                        Log.d(TAG, "peerRead error, break")
                        break // FIXME
                    }
                }

                // check peer side read
                if (!peerReadStarted) {
                    peerReadStarted = true
                    peerRead.start()
                } else if (peerRead.readError) {
                    Log.d(TAG, "B: peerRead error")
                } else if (peerRead.isInterrupted) {
                    Log.d(TAG, "B: peerRead interrupted")
                } else if (!peerRead.isAlive) {
                    Log.wtf(TAG, "B: peerRead stopped unexpectedly!")
                }

                // no data to transfer, break
                if ((clientReadEnd || peerRead.readError)
                    && bufferListForward.isEmpty()
                    && bufferListBackward.isEmpty()
                ) {
                    Log.i(TAG, "no data, break")
                    peerRead.interrupt()
                    break
                }
                i++
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "interrupted")
        } catch (e: IOException) {
            Log.w(TAG, e.toString(), e)
        } finally {
            clientKey.cancel()
            clientSelector.close()
            clientChannel.close()
            peerRead.interrupt()
            peerSocketSSL.close()
            Log.i(TAG, "stopped")
        }
    }

    private class PeerReadThread(
        private val peerSocketSSL: SSLSocket,
        private val bufferListBackward: ConcurrentLinkedQueue<ByteBuffer>,
        private val clientSelector: Selector
    ) : Thread() {
        companion object {
            private val TAG = PeerReadThread::class.java.simpleName
        }

        private val peerReadArray =
            ByteArray(ByteBufferPool.BUFFER_SIZE * 4) // 4 ByteBuffer once at max
        @Volatile
        var readError = false
            private set

        override fun run() {
//            super.run()

            try {
                // try read backward
                // ==============================
                while (!isInterrupted()) {
                    var readBytes = 0
                    readBytes = peerSocketSSL.inputStream.read(peerReadArray) // blocking here

                    if (readBytes < 0) {
                        Log.i(TAG, "B: read ended")
                        currentThread().interrupt()
                    } else if (readBytes == 0) {
                        Log.i(TAG, "B: read 0 byte, closed by peer")
                        currentThread().interrupt()
                    } else if (readBytes > 0) {
                        Log.d(TAG, "B: read $readBytes bytes")
                        var pos = 0
                        var buffer = ByteBufferPool.acquire()
                        while (readBytes - pos > 0) {
                            buffer.put(peerReadArray[pos])
                            pos++
                            if (!buffer.hasRemaining()) { // full
                                buffer.flip()
                                bufferListBackward.offer(buffer)
                                buffer = ByteBufferPool.acquire()
                            }
                        }
                        if (buffer.position() > 0) {
                            buffer.flip()
                            bufferListBackward.offer(buffer)
                        } else {
                            ByteBufferPool.release(buffer)
                        }
                        // new data to write back, wake up
                        clientSelector.wakeup()
                    }
                }
                // ==============================
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "B: read timeout")
            } catch (e: SocketException) {
                Log.d(TAG, "B: read socket exception")
            } catch (e: InterruptedException) {
                Log.d(TAG, "B: read interrupted")
            } finally {
                readError = true
                clientSelector.wakeup()
            }
        }
    }
}
