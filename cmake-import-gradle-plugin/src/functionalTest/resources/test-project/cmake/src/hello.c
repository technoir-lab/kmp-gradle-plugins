#include "hello.h"
#include "hello_impl.h"

#include <stdio.h>

void print_debug_build(void) {
    printf("Debug build\n");
}

void hello(void) {
    printf("Hello, world!\n");
#ifdef HELLO_DEBUG
    print_debug_build();
#endif
}
