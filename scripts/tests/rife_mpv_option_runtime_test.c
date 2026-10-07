#include <stdio.h>
#include <stdlib.h>
#include <mpv/client.h>

static int expect_option(mpv_handle *ctx, const char *name, const char *value)
{
    int result = mpv_set_option_string(ctx, name, value);
    if (result != 0) {
        fprintf(stderr, "mpv_set_option_string(%s) returned %d (%s), expected 0\n",
                name, result, mpv_error_string(result));
        return 1;
    }
    printf("accepted root option: %s\n", name);
    return 0;
}

int main(void)
{
    mpv_handle *ctx = mpv_create();
    if (!ctx) {
        fputs("mpv_create() failed\n", stderr);
        return 1;
    }

    int failed = 0;
    failed |= expect_option(ctx, "rife-resident", "no");
    failed |= expect_option(ctx, "rife-model-dir", "/tmp/rife-model");
    failed |= expect_option(ctx, "rife-target-fps", "60");
    failed |= expect_option(ctx, "rife-max-dimension", "0");
    failed |= expect_option(ctx, "display-fps-override", "60.0");
    failed |= expect_option(ctx, "rife-resident", "yes");

    int unknown = mpv_set_option_string(ctx, "rife-option-regression-unknown", "yes");
    if (unknown != MPV_ERROR_OPTION_NOT_FOUND) {
        fprintf(stderr, "unknown-option control returned %d (%s), expected %d\n",
                unknown, mpv_error_string(unknown), MPV_ERROR_OPTION_NOT_FOUND);
        failed = 1;
    } else {
        puts("unknown-option control correctly returned MPV_ERROR_OPTION_NOT_FOUND");
    }

    mpv_terminate_destroy(ctx);
    if (failed)
        return 1;
    puts("RIFE option runtime test passed before mpv_initialize()");
    return 0;
}
