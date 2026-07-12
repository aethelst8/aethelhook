using System;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using Windows.Foundation;
using Windows.Security.Credentials.UI;

namespace AethelHook.Tray;

// Windows Hello gate for the "Pair New Device" button. Windows.Security.Credentials.UI
// is a WinRT API; UserConsentVerifier.RequestVerificationAsync(string) throws on a plain
// Win32/WPF app (it needs a CoreWindow, which desktop apps don't have) - the documented
// workaround is IUserConsentVerifierInterop::RequestVerificationForWindowAsync, obtained
// via the raw WinRT activation ABI (RoGetActivationFactory).
//
// IUserConsentVerifierInterop derives from IInspectable, and .NET Core's interop
// marshaler flatly refuses to build a [ComImport] wrapper for ANY IInspectable-derived
// interface ("Marshalling as IInspectable is not supported in the .NET runtime",
// confirmed live - this isn't specific to a particular parameter, it fails on
// Marshal.GetObjectForIUnknown()-then-cast for the interface itself). So this doesn't use
// [ComImport] at all: it reads the object's vtable pointer directly and invokes the
// target slot as a raw function pointer - the same low-level technique CsWinRT's own
// generated code uses internally, just done by hand for this one non-projected interface.
// Vtable layout: IUnknown contributes slots 0-2 (QueryInterface/AddRef/Release),
// IInspectable adds slots 3-5 (GetIids/GetRuntimeClassName/GetTrustLevel), so
// RequestVerificationForWindowAsync - IUserConsentVerifierInterop's only method - is slot 6.
public static class WindowsHello
{
    private static readonly Guid InteropIid = new("39E050C3-4E74-441A-8DC0-B81104DF949C");
    private const int RequestVerificationForWindowAsyncSlot = 6;

    // The fixed, universal IInspectable IID (every WinRT object supports it) - requesting
    // this instead of computing IAsyncOperation<UserConsentVerificationResult>'s own
    // parameterized-generic IID by hand, which returned two different values from two
    // different computation methods, both rejected with E_NOINTERFACE (confirmed live).
    // WinRT.MarshalInspectable<T> below is CsWinRT's own supported helper for wrapping a
    // raw IInspectable* into a specific projected type, handling whatever further
    // interface resolution is needed internally instead of guessing it ourselves.
    private static readonly Guid IInspectableIid = new("AF86E2E0-B12D-4c6a-9C5A-D7AA65101E90");

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int RequestVerificationForWindowAsyncFn(
        IntPtr thisPtr, IntPtr appWindow, IntPtr message, ref Guid riid, out IntPtr operation);

    [DllImport("api-ms-win-core-winrt-l1-1-0.dll", ExactSpelling = true, PreserveSig = false)]
    private static extern void RoGetActivationFactory(
        IntPtr activatableClassId,
        [In] ref Guid iid,
        out IntPtr factory);

    // .NET Core's interop marshaler doesn't implement UnmanagedType.HString at all - it
    // throws MarshalDirectiveException on ANY interop signature that uses it (confirmed
    // live). HSTRINGs must be built/freed manually via these raw combase.dll exports and
    // passed around as plain IntPtr handles instead.
    [DllImport("combase.dll", PreserveSig = false)]
    private static extern void WindowsCreateString(
        [MarshalAs(UnmanagedType.LPWStr)] string sourceString, int length, out IntPtr hstring);

    [DllImport("combase.dll")]
    private static extern int WindowsDeleteString(IntPtr hstring);

    public enum HelloResult { Verified, NotConfigured, DeniedOrFailed }

    private static readonly string LogPath = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AethelHook", "tray.log");

    private static void Log(string msg)
    {
        try { System.IO.File.AppendAllText(LogPath, $"{DateTime.Now:HH:mm:ss} [WindowsHello] {msg}{Environment.NewLine}"); }
        catch { /* diagnostics only - never let logging itself break pairing */ }
    }

    // NotConfigured covers both "Hello isn't enrolled on this PC" and "the WinRT
    // interop itself failed for any reason" - both fall back to no gate rather than
    // bricking pairing, per the explicit product decision for this feature. Logged to
    // a plain file (not Debug.WriteLine, which is invisible outside an attached
    // debugger) since this exact interop path has no prior track record in this
    // codebase and needed several live diagnostic passes to get working.
    public static async Task<HelloResult> RequestAsync(IntPtr hwnd, string reason)
    {
        var hClassId   = IntPtr.Zero;
        var hReason    = IntPtr.Zero;
        var factoryPtr = IntPtr.Zero;
        try
        {
            var availability = await UserConsentVerifier.CheckAvailabilityAsync();
            Log($"CheckAvailabilityAsync -> {availability}");
            if (availability != UserConsentVerifierAvailability.Available)
                return HelloResult.NotConfigured;

            const string classId = "Windows.Security.Credentials.UI.UserConsentVerifier";
            WindowsCreateString(classId, classId.Length, out hClassId);
            WindowsCreateString(reason, reason.Length, out hReason);

            var iid = InteropIid;
            RoGetActivationFactory(hClassId, ref iid, out factoryPtr);
            Log($"Got activation factory ptr=0x{factoryPtr.ToInt64():X}, invoking vtable slot {RequestVerificationForWindowAsyncSlot} directly...");

            var vtable    = Marshal.ReadIntPtr(factoryPtr);
            var methodPtr = Marshal.ReadIntPtr(vtable, RequestVerificationForWindowAsyncSlot * IntPtr.Size);
            var method    = Marshal.GetDelegateForFunctionPointer<RequestVerificationForWindowAsyncFn>(methodPtr);

            var operationIid = IInspectableIid;
            var hr = method(factoryPtr, hwnd, hReason, ref operationIid, out var asyncOpPtr);
            Log($"Raw vtable call returned HRESULT=0x{hr:X8}, operation ptr=0x{asyncOpPtr.ToInt64():X}");
            if (hr != 0) throw new COMException("RequestVerificationForWindowAsync failed", hr);
            if (asyncOpPtr == IntPtr.Zero) throw new InvalidOperationException("Got a null operation pointer despite S_OK");

            var asyncOp = WinRT.MarshalInspectable<IAsyncOperation<UserConsentVerificationResult>>.FromAbi(asyncOpPtr);
            Log("Wrapped operation pointer, awaiting result...");
            var result = await asyncOp;
            Log($"RequestVerificationForWindowAsync -> {result}");
            return result == UserConsentVerificationResult.Verified ? HelloResult.Verified : HelloResult.DeniedOrFailed;
        }
        catch (Exception ex)
        {
            Log($"Unavailable or failed: {ex}");
            return HelloResult.NotConfigured;
        }
        finally
        {
            if (hClassId != IntPtr.Zero) WindowsDeleteString(hClassId);
            if (hReason != IntPtr.Zero) WindowsDeleteString(hReason);
            if (factoryPtr != IntPtr.Zero) Marshal.Release(factoryPtr);
        }
    }
}
