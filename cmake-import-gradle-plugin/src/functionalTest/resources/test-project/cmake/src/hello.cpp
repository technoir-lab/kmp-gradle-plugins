#include "hello.h"
#include "hello_impl.h"

#include <iostream>
#include <string>

namespace {
[[gnu::noinline]] void print_message(const char* value) {
    std::string message(value);
    std::cout << message;
}
}

void hello(void) {
    print_message("Hello, world!\n");
#ifdef HELLO_DEBUG
    print_debug_build();
#endif
}
