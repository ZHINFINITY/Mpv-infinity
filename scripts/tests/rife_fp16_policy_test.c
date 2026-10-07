/* SPDX-License-Identifier: AGPL-3.0-or-later */

#include <assert.h>
#include <stdio.h>

#include "rife_fp16_policy.h"

static void test_fp16_arithmetic_policy(void)
{
    assert(!rife_fp16_arithmetic_is_usable(false, true, false));
    assert(!rife_fp16_arithmetic_is_usable(true, false, false));
    assert(!rife_fp16_arithmetic_is_usable(true, true, true));
    assert(!rife_fp16_arithmetic_is_usable(false, false, true));
    assert(rife_fp16_arithmetic_is_usable(true, true, false));
}

int main(void)
{
    test_fp16_arithmetic_policy();
    puts("RIFE FP16 arithmetic policy tests passed");
    return 0;
}
