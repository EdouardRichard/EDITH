// TEMPORARY survey probe (2026-10-16). Mirrors the delivery app's `IRemoteShell` shape: one reserved destroy()
// transaction plus one method carrying the command's whole outcome back as a single String.
package edith.survey.shizukuprobe;

interface IProbeShell {

    void destroy() = 16777114;

    String run(String command) = 1;
}
