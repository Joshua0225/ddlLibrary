// IRemoteService.aidl
package com.ddl.ivygateap;
import com.ddl.ivygateap.IRemoteCallback;

// Declare any non-default types here with import statements

interface IRemoteService {

    void register(IRemoteCallback callback);
    void unRegister(IRemoteCallback callback);

    String getVpnState();
    boolean isVpnRunning();

    void startVpn();
    void stopVpn();
}
