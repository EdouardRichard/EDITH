// P3.3 Task 8: the privileged shell runs in Shizuku's elevated process, so the app talks to it across a binder. The
// shape - a reserved `destroy()` transaction plus our own method - is the vendor demo's
// (`D:\Project_new\Shizuku-API\demo\src\main\aidl\rikka\shizuku\demo\IUserService.aidl`), not an invented one.
package com.sheen.rokid.phone.actions.shizuku;

interface IRemoteShell {

    void destroy() = 16777114; // Reserved by the Shizuku server: called when the user service is replaced or stopped.

    String run(String command) = 1;
}
