LIBNAME := dcgcv
ifeq ($(shell uname),Darwin)
	EXT := dylib
else
	EXT := so
endif

all: target/release/lib$(LIBNAME).$(EXT)

target/release/lib$(LIBNAME).$(EXT): src/lib.rs Cargo.toml
	cargo clean
	DYLD_FALLBACK_LIBRARY_PATH=/usr/local/opt/llvm/lib/ cargo build -r
	(cp ./target/release/lib$(LIBNAME).$(EXT) ./resources/)

clean:
	rm target/release/lib$(LIBNAME).$(EXT)
	cargo clean
