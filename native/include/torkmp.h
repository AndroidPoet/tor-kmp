// C declarations of the Rust functions in native/src/lib.rs, for Kotlin/Native
// cinterop. See lib.rs for the full contract of each function.
//
// Every call blocks. Failures return NULL or a negative number; read the reason
// with torkmp_last_error() on the same thread, right after the call.

#ifndef TORKMP_H
#define TORKMP_H

#include <stddef.h>
#include <stdint.h>

typedef struct TorKmp TorKmp;
typedef struct TorKmpStream TorKmpStream;

TorKmp *torkmp_create(const char *state_dir, const char *cache_dir);
int32_t torkmp_bootstrap(const TorKmp *tor);
void torkmp_cancel_bootstrap(const TorKmp *tor);
void torkmp_status(const TorKmp *tor, float *fraction, int32_t *ready);
char *torkmp_blocked_reason(const TorKmp *tor);
int32_t torkmp_blocked_kind(const TorKmp *tor);
void torkmp_new_identity(const TorKmp *tor);
void torkmp_destroy(TorKmp *tor);

TorKmpStream *torkmp_connect(const TorKmp *tor, const char *host, uint16_t port,
                             const char *isolation);
TorKmpStream *torkmp_connect_tls(const TorKmp *tor, const char *host, uint16_t port,
                                 const char *isolation);
int64_t torkmp_stream_read(const TorKmpStream *stream, uint8_t *buffer, size_t len);
int32_t torkmp_stream_write(const TorKmpStream *stream, const uint8_t *buffer, size_t len);
void torkmp_stream_shutdown(const TorKmpStream *stream);
void torkmp_stream_free(TorKmpStream *stream);

int32_t torkmp_socks_start(const TorKmp *tor, uint16_t port, const char *password);
uint64_t torkmp_socks_ip_connects(const TorKmp *tor);
void torkmp_socks_stop(const TorKmp *tor);

char *torkmp_last_error(void);
void torkmp_string_free(char *text);

#endif
