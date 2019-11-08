package com.ddl.ivygateap.xengineSDK.localDNS

import java.io.IOException
import android.util.Log
import java.net.*
import kotlinx.coroutines.*

class DNSServer : Runnable {
    companion object {
        private val TAG = DNSServer::class.java.simpleName
        // fallback to 114.114.114.114:53
        private val upstreamAddr = InetAddress.getByAddress(byteArrayOf(114, 114, 114, 114))
        private val upstreamPort = 53
        var serverPort = 0
            private set
    }
    private var serverSocket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.Default + CoroutineName("DNS"))

    init {
        try {
            serverSocket = DatagramSocket()
            serverPort = serverSocket!!.localPort
            Log.d(TAG, "wait for request on $serverPort/UDP port...")
        } catch (e: SocketException) {
            e.printStackTrace()
        }
    }

    fun close() {
        serverSocket!!.close()
    }

    private suspend fun getResponsePacket(resp: DNSResponsor, packetIn: DatagramPacket): DatagramPacket {
        resp.genResponse()
        val packetResp = DatagramPacket(
            ByteArray(resp.nResponseLength + 62),
            resp.nResponseLength + 62
        )
        packetResp.address = packetIn.address
        packetResp.port = packetIn.port
        packetResp.data = resp.PayloadResponse
        return packetResp
    }

    private suspend fun getUpstreamPacket(packetIn: DatagramPacket): DatagramPacket {
        val queryAddr = packetIn.address
        val queryPort = packetIn.port

        // forwarding socket
        val forwardingSocket = DatagramSocket()
        forwardingSocket.connect(upstreamAddr, upstreamPort)

        // forwarding packet
        packetIn.address = upstreamAddr
        packetIn.port = upstreamPort

        // forwarding
        sendPacket(forwardingSocket, packetIn)
        Log.d(TAG, "Forwarding")

        // receive response
        val packetBack = DatagramPacket(ByteArray(DNSResponsor.nMaxPacketLength), DNSResponsor.nMaxPacketLength)

        // set timeout
        forwardingSocket.soTimeout = 500 // milliseconds
        try {
            forwardingSocket.receive(packetBack)
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "time exceeding")
            packetIn.address = queryAddr
            packetIn.port = queryPort
            return packetIn
        }

        // close forwarding socket
        forwardingSocket.close()

        // send back
        packetBack.address = queryAddr
        packetBack.port = queryPort
        return packetBack
    }

    private fun sendPacket(socket: DatagramSocket, packet: DatagramPacket) {
        try {
            socket.send(packet)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun run() {
        try {
            Log.i(TAG, "listening ...")
            while (!Thread.interrupted() && !serverSocket!!.isClosed) {
                val queryPacket = DatagramPacket(
                    ByteArray(DNSResponsor.nMaxPacketLength),
                    DNSResponsor.nMaxPacketLength
                )

                try {
                    serverSocket!!.receive(queryPacket)
                } catch (e: SocketException) {
                    Log.w(TAG, "socket closed")
                    break
                } catch (e: IOException) {
                    e.printStackTrace()
                    break
                }

                scope.launch {
                    val resp = DNSResponsor(queryPacket)
                    if (WhiteList.isInWhiteList(resp.strHost)) {

                        sendPacket(serverSocket!!, getResponsePacket(resp, queryPacket))
                        Log.d(TAG, "Responsed")

                    } else { // redirect to original DNS server

                        sendPacket(serverSocket!!, getUpstreamPacket(queryPacket))
                        Log.d(TAG, "Responsed")

                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "interrupted")
        } catch (e: IOException) {
            Log.w(TAG, e.toString(), e)
        } finally {
            serverSocket!!.close()
            Log.i(TAG, "stopped")
        }

        scope.cancel()
    }
}
