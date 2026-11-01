#include "hello_impl.h"

#include <stddef.h>

// One symbol from each framework in hello.pc, declared here because the CoreHaptics headers require Objective-C.
extern int AudioQueueDispose(void* queue, unsigned char immediate);
extern const void* const CHHapticPatternKeyEvent;

int has_apple_frameworks(void) {
    // Volatile storage keeps both references in optimized builds without calling into the frameworks.
    const void* volatile symbols[] = {(const void*)&AudioQueueDispose, &CHHapticPatternKeyEvent};
    return symbols[0] != NULL && symbols[1] != NULL;
}
