/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: /usr/local/lib/android/sdk/build-tools/37.0.0/aidl -p/usr/local/lib/android/sdk/platforms/android-37.0/framework.aidl -o/home/runner/work/service/service/interface/build/generated/aidl_source_output_dir/release/out -I/home/runner/work/service/service/interface/src/main/aidl -I/home/runner/work/service/service/interface/src/release/aidl -d/tmp/aidl14695646017989812585.d /home/runner/work/service/service/interface/src/main/aidl/io/github/libxposed/service/HookedProcess.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package io.github.libxposed.service;
/** Information about a process currently hooked by this module. */
public class HookedProcess implements android.os.Parcelable
{
  /**
   * Opaque identifier assigned by the framework. Module apps must only pass this value back to
   * the service and must not infer ordering, lifetime, or process identity from it.
   */
  public long targetId = 0L;
  /** The process uid, provided for display and diagnostics. */
  public int uid = 0;
  /** The process id, provided for display and diagnostics. It must not be used as target identity. */
  public int pid = 0;
  /** The Android process name, provided for display and diagnostics. */
  public java.lang.String processName;
  /** One of TARGET_STATE_*. */
  public int state = 0;
  /**
   * Version code of the module package loaded in this process. This is only a diagnostic value;
   * the framework may use a stronger internal code identity to determine state.
   */
  public long loadedVersionCode = 0L;
  public static final android.os.Parcelable.Creator<HookedProcess> CREATOR = new android.os.Parcelable.Creator<HookedProcess>() {
    @Override
    public HookedProcess createFromParcel(android.os.Parcel _aidl_source) {
      HookedProcess _aidl_out = new HookedProcess();
      _aidl_out.readFromParcel(_aidl_source);
      return _aidl_out;
    }
    @Override
    public HookedProcess[] newArray(int _aidl_size) {
      return new HookedProcess[_aidl_size];
    }
  };
  @Override public final void writeToParcel(android.os.Parcel _aidl_parcel, int _aidl_flag)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.writeInt(0);
    _aidl_parcel.writeLong(targetId);
    _aidl_parcel.writeInt(uid);
    _aidl_parcel.writeInt(pid);
    _aidl_parcel.writeString(processName);
    _aidl_parcel.writeInt(state);
    _aidl_parcel.writeLong(loadedVersionCode);
    int _aidl_end_pos = _aidl_parcel.dataPosition();
    _aidl_parcel.setDataPosition(_aidl_start_pos);
    _aidl_parcel.writeInt(_aidl_end_pos - _aidl_start_pos);
    _aidl_parcel.setDataPosition(_aidl_end_pos);
  }
  public final void readFromParcel(android.os.Parcel _aidl_parcel)
  {
    int _aidl_start_pos = _aidl_parcel.dataPosition();
    int _aidl_parcelable_size = _aidl_parcel.readInt();
    try {
      if (_aidl_parcelable_size < 4) throw new android.os.BadParcelableException("Parcelable too small");;
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      targetId = _aidl_parcel.readLong();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      uid = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      pid = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      processName = _aidl_parcel.readString();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      state = _aidl_parcel.readInt();
      if (_aidl_parcel.dataPosition() - _aidl_start_pos >= _aidl_parcelable_size) return;
      loadedVersionCode = _aidl_parcel.readLong();
    } finally {
      if (_aidl_start_pos > (Integer.MAX_VALUE - _aidl_parcelable_size)) {
        throw new android.os.BadParcelableException("Overflow in the size of parcelable");
      }
      _aidl_parcel.setDataPosition(_aidl_start_pos + _aidl_parcelable_size);
    }
  }
  /** The target is running the currently installed module code. */
  public static final int TARGET_STATE_UP_TO_DATE = 0;
  /** The target is still running old module code and may be hot-reloaded. */
  public static final int TARGET_STATE_STALE = 1;
  /** The target is currently being hot-reloaded. */
  public static final int TARGET_STATE_RELOADING = 2;
  /**
   * The target's last hot reload attempt failed because the old module refused reload or reload
   * raised an exception.
   */
  public static final int TARGET_STATE_FAILED = 3;
  @Override
  public int describeContents() {
    int _mask = 0;
    return _mask;
  }
}
