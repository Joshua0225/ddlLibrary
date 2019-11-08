package com.ddl.ivygateap.secondarydevelopment

/**
 * @Package:        com.ddl.ivygateap.secondarydevelopment
 * @ClassName:      DDLVpnStateChangeListener
 * @Description:
 * @Author:          Kevin
 * @CreateDate:     2019-11-03 18:18
 */
interface DDLVpnStateChangeListener {
    fun ddlVpnStateChanged(state: DDLVpnState)

    fun ddlServiceConnected()

    fun ddlServiceDisconnected()
}