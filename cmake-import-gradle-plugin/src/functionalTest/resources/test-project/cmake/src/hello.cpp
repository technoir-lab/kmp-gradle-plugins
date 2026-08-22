#include "hello.h"
#include "hello_impl.h"

#include <cstdio>
#include <string>

namespace {
[[gnu::noinline]] void print_message(const char* value) {
    std::string message(value);
    message.reserve(64);
    std::fputs(message.c_str(), stdout);
}
}

void hello(void) {
    print_message("Hello, world!\n");
#ifdef HELLO_DEBUG
    print_debug_build();
#endif
}
