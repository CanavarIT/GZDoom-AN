// Android specific glue between the Java activity (com.az.doom.GZDoomActivity)
// and the engine.

#include <jni.h>

extern bool GUICapture;

// Returns bit 0 set while a menu, the console or another GUI has captured the
// input. The touch overlay (Java) hides its on-screen controls in that case so
// that taps reach the menus as normal mouse clicks.
extern "C" JNIEXPORT jint JNICALL Java_com_az_doom_GZDoomActivity_nativeUiState(JNIEnv*, jclass)
{
	return GUICapture ? 1 : 0;
}
