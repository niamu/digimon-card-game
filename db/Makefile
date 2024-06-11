LIBNAME := dcgcv
ifeq ($(shell uname),Darwin)
	EXT := dylib
else
	EXT := so
endif

all: clean target/release/lib$(LIBNAME).$(EXT)

target/release/lib$(LIBNAME).$(EXT): src/lib.rs Cargo.toml
	(cp ./target/release/lib$(LIBNAME).$(EXT) ./resources/)

clean:
	rm -f target/release/lib$(LIBNAME).$(EXT)
	cargo clean
