#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <errno.h>
#include <wchar.h>

/*
 * libytdl: A native bridge for yt-dlp on Android 10+
 * This executable hosts the Python interpreter by loading libpython.so dynamically.
 * It bypasses the "no exec from data directory" restriction by living in lib/.
 */

typedef int (*Py_BytesMain_t)(int argc, char **argv);
typedef void (*Py_SetProgramName_t)(const wchar_t *);

int main(int argc, char *argv[]) {
    char *python_lib = getenv("YTDL_PYTHON");
    char *script_path = getenv("YTDL_SCRIPT");

    // Use a hardcoded fallback if env var is missing
    if (!python_lib || strlen(python_lib) == 0) {
        python_lib = "libpython.so";
    }

    // Load the Python shared library
    void *handle = dlopen(python_lib, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        handle = dlopen("libpython.so", RTLD_NOW | RTLD_GLOBAL);
    }

    if (!handle) {
        fprintf(stderr, "libytdl: CRITICAL: Could not load libpython.so: %s\n", dlerror());
        return 127;
    }

    // Optional: Set program name to the binary path to help Python find its libs
    Py_SetProgramName_t Py_SetProgramName = (Py_SetProgramName_t)dlsym(handle, "Py_SetProgramName");
    if (Py_SetProgramName) {
        // Simple conversion for the program name
        wchar_t wprog[512];
        mbstowcs(wprog, argv[0], 511);
        wprog[511] = L'\0';
        Py_SetProgramName(wprog);
    }

    // Find Py_BytesMain (Standard entry point for Python 3.8+)
    Py_BytesMain_t Py_BytesMain = (Py_BytesMain_t)dlsym(handle, "Py_BytesMain");
    if (!Py_BytesMain) {
        // Fallback for older Python 3 versions (Py_Main uses wchar_t**, which is harder to use here)
        // But since we expect Python 3.8+, Py_BytesMain should be there.
        fprintf(stderr, "libytdl: CRITICAL: Could not find Py_BytesMain in libpython.so\n");
        dlclose(handle);
        return 127;
    }

    int result;
    if (script_path && strlen(script_path) > 0) {
        // Correct way to run a script: python script.py arg1 arg2 ...
        // We need original_argc + 1 slots for: "python", script_path, argv[1...], and NULL
        int new_argc = argc + 1;
        char **python_argv = malloc((new_argc + 1) * sizeof(char *));
        if (!python_argv) return 1;

        python_argv[0] = "python";
        python_argv[1] = script_path;
        for (int i = 1; i < argc; i++) {
            python_argv[i + 1] = argv[i];
        }
        python_argv[new_argc] = NULL;

        result = Py_BytesMain(new_argc, python_argv);
        free(python_argv);
    } else {
        // If YTDL_SCRIPT is not set, we act as a transparent python interpreter.
        result = Py_BytesMain(argc, argv);
    }

    // Note: We don't dlclose(handle) here as Py_BytesMain might have registered
    // atexit handlers that need the library to remain loaded until process exit.
    return result;
}
