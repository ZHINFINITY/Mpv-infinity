#include <jni.h>
#include <curl/curl.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>

#define MAX_BODY_BYTES (2 * 1024 * 1024)
#define MAX_HEADER_BYTES (256 * 1024)
#define MAX_HEADER_COUNT 64

struct buffer {
    char *data;
    size_t len;
    size_t cap;
};

static int buffer_append(struct buffer *b, const char *s, size_t n) {
    if (b->len >= MAX_BODY_BYTES - 1) {
        return 0;
    }
    if (n > MAX_BODY_BYTES - b->len - 1) {
        n = MAX_BODY_BYTES - b->len - 1;
    }
    size_t needed = b->len + n + 1;
    if (needed > b->cap) {
        size_t new_cap = b->cap ? b->cap * 2 : 4096;
        while (new_cap < needed) new_cap *= 2;
        char *p = realloc(b->data, new_cap);
        if (!p) return -1;
        b->data = p;
        b->cap = new_cap;
    }
    memcpy(b->data + b->len, s, n);
    b->len += n;
    b->data[b->len] = '\0';
    return (int)n;
}

static size_t write_cb(char *ptr, size_t size, size_t nmemb, void *userdata) {
    size_t total = size * nmemb;
    int written = buffer_append((struct buffer *)userdata, ptr, total);
    return written < 0 ? 0 : (size_t)written;
}

static size_t header_cb(char *ptr, size_t size, size_t nmemb, void *userdata) {
    struct buffer *b = (struct buffer *)userdata;
    size_t total = size * nmemb;
    if (b->len + total + 1 > MAX_HEADER_BYTES) {
        return 0;
    }
    int written = buffer_append(b, ptr, total);
    return written < 0 ? 0 : (size_t)written;
}

static int contains_crlf(const char *s) {
    if (!s) return 0;
    while (*s) {
        if (*s == '\r' || *s == '\n') return 1;
        s++;
    }
    return 0;
}

static int is_valid_header_key(const char *s) {
    if (!s || !*s) return 0;
    while (*s) {
        unsigned char c = (unsigned char)*s;
        if (c <= 32 || c >= 127 || c == ':') return 0;
        s++;
    }
    return 1;
}

static int is_allowed_method(const char *method) {
    return strcmp(method, "GET") == 0 ||
           strcmp(method, "HEAD") == 0 ||
           strcmp(method, "POST") == 0 ||
           strcmp(method, "PUT") == 0 ||
           strcmp(method, "PATCH") == 0 ||
           strcmp(method, "DELETE") == 0;
}

#include "cJSON.h"

static jstring to_java_string(JNIEnv *env, const char *str) {
    if (!str) return NULL;
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    if (!strClass) return NULL;
    jmethodID ctor = (*env)->GetMethodID(env, strClass, "<init>", "([BLjava/lang/String;)V");
    if (!ctor) return NULL;
    jstring encoding = (*env)->NewStringUTF(env, "UTF-8");
    if (!encoding) return NULL;

    size_t len = strlen(str);
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)len);
    if (!bytes) return NULL;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)len, (const jbyte*)str);

    jstring result = (jstring)(*env)->NewObject(env, strClass, ctor, bytes, encoding);

    (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, encoding);
    (*env)->DeleteLocalRef(env, strClass);

    return result;
}

static char *build_response_json(long status, struct buffer *body,
                                 struct buffer *raw_headers, const char *error) {
    cJSON *root = cJSON_CreateObject();
    if (!root) return NULL;

    cJSON_AddNumberToObject(root, "status", (double)status);

    if (body && body->data && body->len > 0) {
        cJSON_AddStringToObject(root, "body", body->data);
    } else {
        cJSON_AddStringToObject(root, "body", "");
    }

    cJSON *hdrs = cJSON_CreateObject();
    if (hdrs) {
        if (raw_headers && raw_headers->data) {
            char *hdr = strdup(raw_headers->data);
            if (hdr) {
                char *save;
                char *line = strtok_r(hdr, "\r\n", &save);
                while (line) {
                    if (strncmp(line, "HTTP/", 5) == 0) {
                        line = strtok_r(NULL, "\r\n", &save);
                        continue;
                    }
                    char *colon = strchr(line, ':');
                    if (colon) {
                        *colon = '\0';
                        char *key = line;
                        char *val = colon + 1;
                        while (*val == ' ') val++;
                        cJSON_AddStringToObject(hdrs, key, val);
                    }
                    line = strtok_r(NULL, "\r\n", &save);
                }
                free(hdr);
            }
        }
        cJSON_AddItemToObject(root, "headers", hdrs);
    }

    if (error) {
        cJSON_AddStringToObject(root, "error", error);
    } else {
        cJSON_AddNullToObject(root, "error");
    }

    char *json_str = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);

    return json_str;
}

static int curl_initialised = 0;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    curl_global_init(CURL_GLOBAL_DEFAULT);
    curl_initialised = 1;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    if (curl_initialised) {
        curl_global_cleanup();
        curl_initialised = 0;
    }
}

JNIEXPORT jstring JNICALL
Java_app_gyrolet_mpvrx_ui_player_ScriptCurlBridge_nativeExecute(
    JNIEnv *env, jobject thiz,
    jstring j_url, jstring j_method,
    jobjectArray j_header_keys, jobjectArray j_header_values,
    jstring j_body, jstring j_content_type, jint j_timeout) {

    const char *url   = j_url   ? (*env)->GetStringUTFChars(env, j_url, NULL) : "";
    const char *meth  = j_method ? (*env)->GetStringUTFChars(env, j_method, NULL) : "GET";
    const char *body  = j_body  ? (*env)->GetStringUTFChars(env, j_body, NULL) : NULL;
    const char *ctype = j_content_type ? (*env)->GetStringUTFChars(env, j_content_type, NULL) : NULL;

    jstring result = NULL;

    if (!url || !*url) {
        result = to_java_string(env,
            "{\"status\":0,\"body\":\"\",\"headers\":{},\"error\":\"URL must not be blank\"}");
        goto cleanup_strings;
    }

    if (!meth || !is_allowed_method(meth)) {
        result = to_java_string(env,
            "{\"status\":0,\"body\":\"\",\"headers\":{},\"error\":\"Unsupported HTTP method\"}");
        goto cleanup_strings;
    }

    if (contains_crlf(ctype)) {
        result = to_java_string(env,
            "{\"status\":0,\"body\":\"\",\"headers\":{},\"error\":\"Invalid content type\"}");
        goto cleanup_strings;
    }

    jsize hcount = j_header_keys ? (*env)->GetArrayLength(env, j_header_keys) : 0;
    jsize hvcount = j_header_values ? (*env)->GetArrayLength(env, j_header_values) : 0;
    if (hcount != hvcount) {
        result = to_java_string(env,
            "{\"status\":0,\"body\":\"\",\"headers\":{},\"error\":\"Header key/value count mismatch\"}");
        goto cleanup_strings;
    }
    if (hcount > MAX_HEADER_COUNT) {
        result = to_java_string(env,
            "{\"status\":0,\"body\":\"\",\"headers\":{},\"error\":\"Too many headers\"}");
        goto cleanup_strings;
    }

    CURL *curl = curl_easy_init();
    if (!curl) {
        result = to_java_string(env,
            "{\"status\":0,\"body\":\"\",\"headers\":{},\"error\":\"Failed to init libcurl\"}");
        goto cleanup_strings;
    }

    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_MAXREDIRS, 10L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, (long)j_timeout);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, (long)j_timeout);
    curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "http,https");
    curl_easy_setopt(curl, CURLOPT_REDIR_PROTOCOLS_STR, "http,https");
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl, CURLOPT_USERAGENT, "mpvRx-script-curl/1.0");

    struct buffer resp_body = {0};
    struct buffer resp_headers = {0};
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_cb);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &resp_body);
    curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, header_cb);
    curl_easy_setopt(curl, CURLOPT_HEADERDATA, &resp_headers);

    if (strcmp(meth, "GET") == 0) {
        curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
    } else if (strcmp(meth, "HEAD") == 0) {
        curl_easy_setopt(curl, CURLOPT_NOBODY, 1L);
    } else if (strcmp(meth, "POST") == 0) {
        curl_easy_setopt(curl, CURLOPT_POST, 1L);
    } else if (strcmp(meth, "PUT") == 0 || strcmp(meth, "PATCH") == 0 || strcmp(meth, "DELETE") == 0) {
        curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, meth);
    }

    if (body && (strcmp(meth, "POST") == 0 || strcmp(meth, "PUT") == 0 || strcmp(meth, "PATCH") == 0)) {
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body);
        curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)strlen(body));
    }

    struct curl_slist *chunk = NULL;
    if (ctype) {
        char h[512];
        snprintf(h, sizeof(h), "Content-Type: %s", ctype);
        chunk = curl_slist_append(chunk, h);
    }

    for (int i = 0; i < hcount; i++) {
        jstring jk = (*env)->GetObjectArrayElement(env, j_header_keys, i);
        jstring jv = (*env)->GetObjectArrayElement(env, j_header_values, i);
        if (!jk || !jv) {
            if (jk) (*env)->DeleteLocalRef(env, jk);
            if (jv) (*env)->DeleteLocalRef(env, jv);
            result = to_java_string(env,
                "{\"status\":0,\"body\":\"\",\"headers\":{},\"error\":\"Invalid header\"}");
            goto cleanup_curl;
        }
        const char *k = (*env)->GetStringUTFChars(env, jk, NULL);
        const char *v = (*env)->GetStringUTFChars(env, jv, NULL);
        if (!is_valid_header_key(k) || contains_crlf(v)) {
            if (k) (*env)->ReleaseStringUTFChars(env, jk, k);
            if (v) (*env)->ReleaseStringUTFChars(env, jv, v);
            (*env)->DeleteLocalRef(env, jk);
            (*env)->DeleteLocalRef(env, jv);
            result = to_java_string(env,
                "{\"status\":0,\"body\":\"\",\"headers\":{},\"error\":\"Invalid header\"}");
            goto cleanup_curl;
        }
        char h[4096];
        snprintf(h, sizeof(h), "%s: %s", k, v);
        chunk = curl_slist_append(chunk, h);
        (*env)->ReleaseStringUTFChars(env, jk, k);
        (*env)->ReleaseStringUTFChars(env, jv, v);
        (*env)->DeleteLocalRef(env, jk);
        (*env)->DeleteLocalRef(env, jv);
    }
    if (chunk) curl_easy_setopt(curl, CURLOPT_HTTPHEADER, chunk);

    CURLcode res = curl_easy_perform(curl);
    long http_code = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http_code);

    const char *err = NULL;
    if (res != CURLE_OK) {
        err = curl_easy_strerror(res);
    }

    char *json = build_response_json(http_code, &resp_body, &resp_headers, err);
    if (json) {
        result = to_java_string(env, json);
        free(json);
    } else {
        result = to_java_string(env,
            "{\"status\":0,\"body\":\"\",\"headers\":{},\"error\":\"OOM building response\"}");
    }

cleanup_curl:
    curl_easy_cleanup(curl);
    if (chunk) curl_slist_free_all(chunk);
    free(resp_body.data);
    free(resp_headers.data);

cleanup_strings:
    if (j_url && url)   (*env)->ReleaseStringUTFChars(env, j_url, url);
    if (j_method && meth) (*env)->ReleaseStringUTFChars(env, j_method, meth);
    if (j_body && body)  (*env)->ReleaseStringUTFChars(env, j_body, body);
    if (j_content_type && ctype) (*env)->ReleaseStringUTFChars(env, j_content_type, ctype);

    return result;
}
