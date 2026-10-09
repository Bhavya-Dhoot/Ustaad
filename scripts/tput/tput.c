// Minimal TCP throughput probe for Android (NDK). server: tput s PORT ; client: tput c IP PORT MB
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
static double now(){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
int main(int argc,char**argv){
  static char buf[1<<20]; int one=1;
  if(argc>=3&&argv[1][0]=='s'){
    int ls=socket(AF_INET,SOCK_STREAM,0); setsockopt(ls,SOL_SOCKET,SO_REUSEADDR,&one,sizeof one);
    struct sockaddr_in a={0}; a.sin_family=AF_INET; a.sin_addr.s_addr=INADDR_ANY; a.sin_port=htons(atoi(argv[2]));
    if(bind(ls,(void*)&a,sizeof a)||listen(ls,4)){perror("bind");return 1;}
    for(;;){ int c=accept(ls,0,0); if(c<0)continue; long long tot=0; ssize_t n; double t0=0,t1;
      while((n=read(c,buf,sizeof buf))>0){ if(!tot)t0=now(); tot+=n; }
      t1=now(); double s=t1-t0; printf("rx %lld bytes in %.3f s = %.1f Mbps\n",tot,s,s>0?tot*8/s/1e6:0); fflush(stdout); close(c);
      if(argc>3) break; }
    return 0; }
  if(argc>=5&&argv[1][0]=='c'){
    int s=socket(AF_INET,SOCK_STREAM,0); setsockopt(s,IPPROTO_TCP,TCP_NODELAY,&one,sizeof one);
    struct sockaddr_in a={0}; a.sin_family=AF_INET; inet_pton(AF_INET,argv[2],&a.sin_addr); a.sin_port=htons(atoi(argv[3]));
    if(connect(s,(void*)&a,sizeof a)){perror("connect");return 1;}
    long long want=(long long)atoll(argv[4])<<20, sent=0; double t0=now();
    while(sent<want){ ssize_t n=write(s,buf,sizeof buf); if(n<=0){perror("write");break;} sent+=n; }
    shutdown(s,SHUT_WR); char x; while(read(s,&x,1)>0); double el=now()-t0;
    printf("tx %lld bytes in %.3f s = %.1f Mbps\n",sent,el,sent*8/el/1e6); return 0; }
  // latency: tput p IP PORT N  (ping-pong 1 byte N times against server started as 'tput e PORT')
  if(argc>=5&&argv[1][0]=='p'){
    int s=socket(AF_INET,SOCK_STREAM,0); setsockopt(s,IPPROTO_TCP,TCP_NODELAY,&one,sizeof one);
    struct sockaddr_in a={0}; a.sin_family=AF_INET; inet_pton(AF_INET,argv[2],&a.sin_addr); a.sin_port=htons(atoi(argv[3]));
    if(connect(s,(void*)&a,sizeof a)){perror("connect");return 1;}
    int n=atoi(argv[4]); double mn=1e9,mx=0,sum=0; char x='a';
    for(int i=0;i<n;i++){ double t=now(); write(s,&x,1); read(s,&x,1); double d=(now()-t)*1e3; sum+=d; if(d<mn)mn=d; if(d>mx)mx=d; }
    printf("rtt ms min/avg/max = %.2f/%.2f/%.2f over %d\n",mn,sum/n,mx,n); return 0; }
  if(argc>=3&&argv[1][0]=='e'){ // echo server
    int ls=socket(AF_INET,SOCK_STREAM,0); setsockopt(ls,SOL_SOCKET,SO_REUSEADDR,&one,sizeof one);
    struct sockaddr_in a={0}; a.sin_family=AF_INET; a.sin_addr.s_addr=INADDR_ANY; a.sin_port=htons(atoi(argv[2]));
    if(bind(ls,(void*)&a,sizeof a)||listen(ls,4)){perror("bind");return 1;}
    for(;;){ int c=accept(ls,0,0); setsockopt(c,IPPROTO_TCP,TCP_NODELAY,&one,sizeof one); char x; while(read(c,&x,1)>0) write(c,&x,1); close(c);} }
  fprintf(stderr,"usage: tput s PORT [once] | tput c IP PORT MB | tput e PORT | tput p IP PORT N\n"); return 2; }
