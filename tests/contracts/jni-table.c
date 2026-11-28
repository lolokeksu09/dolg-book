#include <jni.h>
#include <stddef.h>

#define SLOT(name, index) _Static_assert(offsetof(struct JNINativeInterface_, name) == (index) * sizeof(void *), #name " slot mismatch")
SLOT(GetArrayLength, 171);
SLOT(GetLongArrayRegion, 204);
SLOT(SetLongArrayRegion, 212);
SLOT(ExceptionCheck, 228);
int main(void) { return 0; }
