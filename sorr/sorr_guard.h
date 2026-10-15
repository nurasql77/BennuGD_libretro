#ifndef SORR_GUARD_H
#define SORR_GUARD_H

#ifdef __cplusplus
extern "C" {
#endif

/* Returns 1 if the APK that hosts this core is signed with the expected
 * certificate, 0 otherwise. The APK path comes from the SORR_APK_PATH
 * environment variable (set by MainActivity before the core is loaded). */
int sorr_guard_check(void);

#ifdef __cplusplus
}
#endif

#endif