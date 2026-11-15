#define _POSIX_C_SOURCE 200809L
#include "../../app/src/main/cpp/mpvflow/mpvrx_jelly_probe_writer.h"
#include <assert.h>
#include <fcntl.h>
#include <time.h>
#include <pthread.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
struct race { struct mpvrx_jelly_probe_writer *w; unsigned char *record; pthread_barrier_t *barrier; enum mpvrx_jelly_probe_capture_result result; };
static void *racer(void *p) { struct race *r=p; pthread_barrier_wait(r->barrier); r->result=mpvrx_jelly_probe_writer_capture(r->w,r->record); return NULL; }
int main(void) {
 char path[]="/tmp/mpvrx-jelly-writer-XXXXXX"; int fd=mkstemp(path); assert(fd>=0); close(fd); unlink(path);
 struct mpvrx_jelly_probe_writer w; assert(mpvrx_jelly_probe_writer_init(&w,path)==0);
 unsigned char rec[MPVRX_JELLY_PROBE_RECORD_BYTES]={0}; float f[256]={0};
	 uint64_t a=UINT64_C(0xfedcba9876543210), b=UINT64_C(0x123456789abcdef0); uint32_t ids[4]={(uint32_t)a,(uint32_t)(a>>32),(uint32_t)b,(uint32_t)(b>>32)};
	 memcpy(rec+58*16,ids,sizeof ids); memcpy(f+59*4,(float[]){12.5f,25.75f,.375f,1.f},4*sizeof(float)); f[0]=1;f[1]=6;f[57*4+3]=NAN; memcpy(rec,f,sizeof rec);
	 /* Restore raw ID words after float payload initialization. */ memcpy(rec+58*16,ids,sizeof ids);
	 assert(mpvrx_jelly_probe_record_is_activation(rec));
	 float reason=5.0f; memcpy(rec+sizeof(float),&reason,sizeof reason); assert(!mpvrx_jelly_probe_record_is_activation(rec));
	 reason=NAN; memcpy(rec+sizeof(float),&reason,sizeof reason); assert(!mpvrx_jelly_probe_record_is_activation(rec));
	 reason=6.0f; memcpy(rec+sizeof(float),&reason,sizeof reason); assert(mpvrx_jelly_probe_record_is_activation(rec));
	 assert(mpvrx_jelly_probe_writer_capture(&w,rec)==MPVRX_JELLY_CAPTURE_NOT_ARMED);
 assert(mpvrx_jelly_probe_writer_arm(&w)==MPVRX_JELLY_CAPTURE_ACCEPTED);
 assert(mpvrx_jelly_probe_writer_arm(&w)==MPVRX_JELLY_CAPTURE_ALREADY_USED);
 assert(mpvrx_jelly_probe_writer_capture(&w,rec)==MPVRX_JELLY_CAPTURE_ACCEPTED);
 assert(mpvrx_jelly_probe_writer_capture(&w,rec)==MPVRX_JELLY_CAPTURE_ALREADY_USED);
 /* Completion is published independently of VO/control teardown. */
 for(int i=0;i<3000 && mpvrx_jelly_probe_writer_status(&w)!=MPVRX_JELLY_PROBE_COMPLETE;i++) { struct timespec ts={0,1000000}; nanosleep(&ts,NULL); }
 assert(mpvrx_jelly_probe_writer_status(&w)==MPVRX_JELLY_PROBE_COMPLETE);
 fd=open(path,0); assert(fd>=0); char json[16384]; ssize_t n=read(fd,json,sizeof json-1); close(fd); assert(n>0);json[n]=0;
 assert(strstr(json,"\"schema_version\":1")); assert(strstr(json,"\"id0\":\"18364758544493064720\"")); assert(strstr(json,"\"id1\":\"1311768467463790320\"")); assert(strstr(json,"12.5")); assert(strstr(json,"25.75")); assert(strstr(json,"\"neighbors\":[")); assert(strstr(json,"\"rejection\":0")); assert(strstr(json,"null"));
 mpvrx_jelly_probe_writer_request_stop(&w); assert(mpvrx_jelly_probe_writer_join(&w)==0); mpvrx_jelly_probe_writer_destroy(&w); unlink(path);
	 puts("ok activation gate, one-shot arm/capture, fixed-slot wake, structured JSON, exact IDs/PTS, NaN null, atomic report, safe joined destroy");
 struct mpvrx_jelly_probe_writer bad; assert(mpvrx_jelly_probe_writer_init(&bad,NULL)!=0);
 puts("ok failed init");
 char racepath[]="/tmp/mpvrx-jelly-race-XXXXXX"; fd=mkstemp(racepath); assert(fd>=0); close(fd); unlink(racepath);
 struct mpvrx_jelly_probe_writer rw; assert(mpvrx_jelly_probe_writer_init(&rw,racepath)==0); assert(mpvrx_jelly_probe_writer_arm(&rw)==MPVRX_JELLY_CAPTURE_ACCEPTED);
 pthread_barrier_t barrier; assert(pthread_barrier_init(&barrier,NULL,2)==0); unsigned char rr[MPVRX_JELLY_PROBE_RECORD_BYTES]={0}; struct race r={&rw,rr,&barrier,MPVRX_JELLY_CAPTURE_INVALID}; pthread_t t; assert(pthread_create(&t,NULL,racer,&r)==0); pthread_barrier_wait(&barrier); mpvrx_jelly_probe_writer_request_stop(&rw); assert(pthread_join(t,NULL)==0); pthread_barrier_destroy(&barrier);
 assert(r.result==MPVRX_JELLY_CAPTURE_ACCEPTED || r.result==MPVRX_JELLY_CAPTURE_CLOSED || r.result==MPVRX_JELLY_CAPTURE_ALREADY_USED);
 assert(mpvrx_jelly_probe_writer_join(&rw)==0); mpvrx_jelly_probe_writer_destroy(&rw); unlink(racepath); puts("ok concurrent close versus callback"); return 0;
}
