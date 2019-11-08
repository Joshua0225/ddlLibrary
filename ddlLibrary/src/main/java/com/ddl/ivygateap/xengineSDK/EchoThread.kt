package com.ddl.ivygateap.xengineSDK

import com.ddl.ivygateap.xengineSDK.localDNS.IPPool
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.*

internal class EchoThread(private val clientChannel: SocketChannel) : Runnable {
    companion object {
        private val TAG = EchoThread::class.java.simpleName
    }

    private val bufferListForward = LinkedList<ByteBuffer>()
    private val bufferListBackward = LinkedList<ByteBuffer>()
    private val peerChannel = SocketChannel.open()
    private val transSSL = SSLTranslator()
    private var useSSL = false
    private var connected = false

    init {
        clientChannel.configureBlocking(false)
        if (SocketServer.uri.scheme == "https") {
            useSSL = true
            transSSL.initSSL("TLSv1.2")
        }
    }

    override fun run() {
        connectChannel()
        if (useSSL) {
            transSSL.runSSLHandshake(peerChannel)
        }
        runTCPForwarding()
    }

    private fun connectChannel() {
        peerChannel.configureBlocking(false)
        peerChannel.connect(InetSocketAddress(SocketServer.uri.host, SocketServer.uri.port))
        //peerChannel.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 227.toByte(), 207.toByte())), 8443))

        // TODO: do connect with selector and SelectionKey.OP_CONNECT ?
        while (!peerChannel.finishConnect()) {
            Thread.sleep(10)
        }
        Log.i(TAG, "peerChannel connected")
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
                Log.d(TAG, "CONNECT to $uri -> $hostString : $portString")
            }

            val bufferNew = ByteBufferPool.acquire()
            bufferNew.put("CONNECT $hostString:$portString HTTP/1.1\r\n".toByteArray())
            bufferNew.put("Host: $hostString:$portString\r\n".toByteArray())
            bufferNew.put("${headers[4]}\r\n".toByteArray()) // keep alive line
            bufferNew.put("${headers[5]}\r\n\r\n".toByteArray()) // basic auth line

            ByteBufferPool.release(buffer)
            return bufferNew.flip() as ByteBuffer
        } else {
            Log.w(TAG, "not a connect request")
            return buffer
        }
    }

    private fun runTCPForwarding() {
        val selectorForward = Selector.open()
        clientChannel.register(selectorForward, SelectionKey.OP_READ)
        peerChannel.register(selectorForward, SelectionKey.OP_WRITE)

        val selectorBackward = Selector.open()
        clientChannel.register(selectorBackward, SelectionKey.OP_WRITE)
        peerChannel.register(selectorBackward, SelectionKey.OP_READ)

        try {
            while (!Thread.interrupted() && clientChannel.isOpen && peerChannel.isOpen) {
                if (!peerChannel.isOpen) {
                    clientChannel.close()
                    break
                }
                if (!clientChannel.isOpen) {
                    peerChannel.close()
                    break
                }

                var clientReadEnd = false
                var peerReadEnd = false

                var noForward = false
                if (selectorForward.selectNow() == 0) {
                    noForward = true
                } else { // process forward
                    val iterForward = selectorForward.selectedKeys().iterator()
                    while (iterForward.hasNext() && !Thread.interrupted()) {
                        val key = iterForward.next()
                        if (key.isValid) {
                            if (key.isReadable) {
                                var buffer = ByteBufferPool.acquire()
                                var readBytes = clientChannel.read(buffer)

                                if (readBytes < 0) {
                                    //Log.d(TAG, "F: end of stream")
                                    clientReadEnd = true
                                }

                                while (readBytes > 0) { // be careful for non-blocked channel with 0 readBytes
                                    Log.d(TAG, "F: read $readBytes bytes")
                                    buffer.flip()
                                    if (connected) {
                                        bufferListForward.add(buffer)
                                    } else {
                                        bufferListForward.add(replaceFakeIP(buffer))
                                        connected = true
                                    }
                                    buffer = ByteBufferPool.acquire()
                                    readBytes = clientChannel.read(buffer)
                                }

                                // release last empty buffer
                                ByteBufferPool.release(buffer)
                            } else if (!bufferListForward.isEmpty() && key.isWritable) {
                                val buffer = bufferListForward.first
                                var bRemaining: Boolean
                                if (useSSL) {
                                    bRemaining = transSSL.write(buffer, peerChannel)
                                } else {
                                    peerChannel.write(buffer)
                                    bRemaining = buffer.hasRemaining()
                                }

//                                Log.v(
//                                    TAG,
//                                    "F: ${String(buffer.array(), Charsets.UTF_8).substring(
//                                        buffer.arrayOffset(),
//                                        buffer.arrayOffset() + buffer.position()
//                                    )}"
//                                )
                                if (bRemaining) {
                                    Log.wtf(TAG, "F: remaining")
                                } else {
                                    Log.d(TAG, "F: written")
                                    // release element
                                    ByteBufferPool.release(bufferListForward.removeFirst())
                                }
                            }
                        }
                        iterForward.remove()
                    }
                }
                if (selectorBackward.selectNow() == 0) {
                    if (noForward) {
                        Thread.sleep(10) // TODO: sleep may not be the best choice
                        continue;
                    }
                } else { // process backward
                    val iterBackward = selectorBackward.selectedKeys().iterator()
                    while (iterBackward.hasNext() && !Thread.interrupted()) {
                        val key = iterBackward.next()
                        if (key.isValid) {
                            if (key.isReadable) {
                                var buffer = ByteBufferPool.acquire()
                                var readBytes = peerChannel.read(buffer)

                                if (readBytes < 0) {
                                    //Log.d(TAG, "B: end of stream")
                                    peerReadEnd = true
                                }

                                while (readBytes > 0) {
                                    Log.d(TAG, "B: read $readBytes bytes")
                                    buffer.flip()
                                    if (useSSL) {
                                        transSSL.read(buffer, bufferListBackward)
                                    } else {
                                        bufferListBackward.add(buffer)
                                    }
                                    buffer = ByteBufferPool.acquire()
                                    readBytes = peerChannel.read(buffer)
                                }

                                // release last empty buffer
                                ByteBufferPool.release(buffer)
                            } else if (!bufferListBackward.isEmpty() && key.isWritable) {
                                val buffer = bufferListBackward.first
                                clientChannel.write(bufferListBackward.first)

//                                Log.v(
//                                    TAG,
//                                    "B: ${String(buffer.array(), Charsets.UTF_8).substring(
//                                        buffer.arrayOffset(),
//                                        buffer.arrayOffset() + buffer.position()
//                                    )}"
//                                )
                                if (buffer.hasRemaining()) {
                                    Log.wtf(TAG, "B: why remaining ?")
                                } else {
                                    Log.d(TAG, "B: written")
                                    // release element
                                    ByteBufferPool.release(bufferListBackward.removeFirst())
                                }
                            }
                        }
                        iterBackward.remove()
                    }
                }
                // no data to transfer, break
                if (clientReadEnd && peerReadEnd
                    && bufferListForward.isEmpty()
                    && bufferListBackward.isEmpty()
                ) {
                    Log.i(TAG, "no data, break")
                    break
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "interrupted")
        } catch (e: IOException) {
            Log.w(TAG, e.toString(), e)
        } finally {
            if (useSSL) {
                transSSL.shutdwon()
            }
            clientChannel.close()
            peerChannel.close()
            selectorForward.close()
            selectorBackward.close()
            Log.i(TAG, "stopped")
        }
    }
}
