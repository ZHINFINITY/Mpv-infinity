#include <vulkan/vulkan.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#define CK(x) do { VkResult r=(x); if(r!=VK_SUCCESS){ exit(2);} } while(0)
typedef struct {VkImage i; VkImageView v; VkDeviceMemory m; uint32_t w,h;} Img;
typedef struct {VkBuffer b; VkDeviceMemory m;} Buf;
typedef struct {VkInstance ins;VkPhysicalDevice pd;VkDevice d;VkQueue q;uint32_t qf;VkCommandPool cp;VkDescriptorSetLayout dl;VkDescriptorPool dp;VkPipelineLayout pl;} Ctx;
static uint16_t hf(float x){_Float16 q=(_Float16)x;uint16_t b;memcpy(&b,&q,2);return b;}
static float fh(uint16_t h){int s=h>>15,e=(h>>10)&31,m=h&1023; if(e==31)return m?NAN:(s?-INFINITY:INFINITY); float v=e?ldexpf(1+m/1024.f,e-15):ldexpf(m/1024.f,-14);return s?-v:v;}
static uint32_t mt(Ctx*c,uint32_t bits,VkMemoryPropertyFlags f){VkPhysicalDeviceMemoryProperties p;vkGetPhysicalDeviceMemoryProperties(c->pd,&p);for(uint32_t i=0;i<p.memoryTypeCount;i++)if((bits>>i&1)&&(p.memoryTypes[i].propertyFlags&f)==f)return i;return UINT32_MAX;}
static void cmd(Ctx*c,void(*fn)(VkCommandBuffer),VkCommandBufferUsageFlags flags){VkCommandBufferAllocateInfo a={VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,0,c->cp,VK_COMMAND_BUFFER_LEVEL_PRIMARY,1};VkCommandBuffer x;CK(vkAllocateCommandBuffers(c->d,&a,&x));VkCommandBufferBeginInfo b={VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,0,flags,0};CK(vkBeginCommandBuffer(x,&b));fn(x);CK(vkEndCommandBuffer(x));VkSubmitInfo s={VK_STRUCTURE_TYPE_SUBMIT_INFO,0,0,0,0,1,&x,0,0};CK(vkQueueSubmit(c->q,1,&s,VK_NULL_HANDLE));CK(vkQueueWaitIdle(c->q));vkFreeCommandBuffers(c->d,c->cp,1,&x);}
static Img *BI; static VkImageLayout BL; static void barrier(VkCommandBuffer x){VkImageMemoryBarrier b={.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT,.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT,.oldLayout=BL,.newLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.image=BI->i,.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};vkCmdPipelineBarrier(x,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,0,0,0,1,&b);}
static void upload_cmd(VkCommandBuffer x){VkImageMemoryBarrier b={.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,.srcAccessMask=0,.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT,.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED,.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.image=BI->i,.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};vkCmdPipelineBarrier(x,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,0,0,0,1,&b);}
static Img image(Ctx*c,uint32_t w,uint32_t h){Img a={0};a.w=w;a.h=h;VkImageCreateInfo z={VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,0,0,VK_IMAGE_TYPE_2D,VK_FORMAT_R16G16B16A16_SFLOAT,{w,h,1},1,1,VK_SAMPLE_COUNT_1_BIT,VK_IMAGE_TILING_OPTIMAL,VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,VK_SHARING_MODE_EXCLUSIVE,0,0,VK_IMAGE_LAYOUT_UNDEFINED};CK(vkCreateImage(c->d,&z,0,&a.i));VkMemoryRequirements r;vkGetImageMemoryRequirements(c->d,a.i,&r);VkMemoryAllocateInfo q={VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,0,r.size,mt(c,r.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)};CK(vkAllocateMemory(c->d,&q,0,&a.m));CK(vkBindImageMemory(c->d,a.i,a.m,0));VkImageViewCreateInfo v={VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,0,0,a.i,VK_IMAGE_VIEW_TYPE_2D,VK_FORMAT_R16G16B16A16_SFLOAT,{VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY},{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};CK(vkCreateImageView(c->d,&v,0,&a.v));return a;}
static Buf buffer(Ctx*c,VkDeviceSize n,VkBufferUsageFlags usage,VkMemoryPropertyFlags prop){Buf a={0};VkBufferCreateInfo z={VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,0,0,n,usage,VK_SHARING_MODE_EXCLUSIVE,0,0};CK(vkCreateBuffer(c->d,&z,0,&a.b));VkMemoryRequirements r;vkGetBufferMemoryRequirements(c->d,a.b,&r);uint32_t ti=mt(c,r.memoryTypeBits,prop);VkMemoryAllocateInfo q={VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,0,r.size,ti};CK(vkAllocateMemory(c->d,&q,0,&a.m));CK(vkBindBufferMemory(c->d,a.b,a.m,0));return a;}
static void copy_to(Ctx*c,Img*a,const void*p,size_t n){Buf b=buffer(c,n,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);void*m;CK(vkMapMemory(c->d,b.m,0,n,0,&m));memcpy(m,p,n);vkUnmapMemory(c->d,b.m);BI=a;BL=VK_IMAGE_LAYOUT_UNDEFINED;void f(VkCommandBuffer x){upload_cmd(x);VkBufferImageCopy k={0,0,0,{VK_IMAGE_ASPECT_COLOR_BIT,0,0,1},{0,0,0},{a->w,a->h,1}};vkCmdCopyBufferToImage(x,b.b,a->i,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&k);VkImageMemoryBarrier q={.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT,.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,.newLayout=VK_IMAGE_LAYOUT_GENERAL,.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED,.image=a->i,.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};vkCmdPipelineBarrier(x,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,0,0,0,0,1,&q);};cmd(c,f,VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);vkDestroyBuffer(c->d,b.b,0);vkFreeMemory(c->d,b.m,0);}
static void readback(Ctx*c,Img*a,void*p,size_t n){Buf b=buffer(c,n,VK_BUFFER_USAGE_TRANSFER_DST_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);BI=a;BL=VK_IMAGE_LAYOUT_GENERAL;void f(VkCommandBuffer x){barrier(x);VkBufferImageCopy k={0,0,0,{VK_IMAGE_ASPECT_COLOR_BIT,0,0,1},{0,0,0},{a->w,a->h,1}};vkCmdCopyImageToBuffer(x,a->i,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,b.b,1,&k);};cmd(c,f,VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);void*m;CK(vkMapMemory(c->d,b.m,0,n,0,&m));memcpy(p,m,n);vkUnmapMemory(c->d,b.m);vkDestroyBuffer(c->d,b.b,0);vkFreeMemory(c->d,b.m,0);}
static void init(Ctx*c){VkApplicationInfo ai={VK_STRUCTURE_TYPE_APPLICATION_INFO,0,"pair",1,"pair",1,VK_API_VERSION_1_1};VkInstanceCreateInfo ii={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&ai};CK(vkCreateInstance(&ii,0,&c->ins));uint32_t n=0;CK(vkEnumeratePhysicalDevices(c->ins,&n,0));VkPhysicalDevice*p=calloc(n,sizeof(*p));CK(vkEnumeratePhysicalDevices(c->ins,&n,p));for(uint32_t i=0;i<n;i++){uint32_t q=0;vkGetPhysicalDeviceQueueFamilyProperties(p[i],&q,0);VkQueueFamilyProperties*f=calloc(q,sizeof(*f));vkGetPhysicalDeviceQueueFamilyProperties(p[i],&q,f);for(uint32_t j=0;j<q;j++)if(f[j].queueFlags&VK_QUEUE_COMPUTE_BIT){c->pd=p[i];c->qf=j;break;}free(f);if(c->pd)break;}free(p);if(!c->pd){fprintf(stderr,"no Vulkan compute device\n");exit(2);}float pr=1;VkDeviceQueueCreateInfo qi={VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,0,0,c->qf,1,&pr};VkDeviceCreateInfo di={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qi};CK(vkCreateDevice(c->pd,&di,0,&c->d));vkGetDeviceQueue(c->d,c->qf,0,&c->q);VkCommandPoolCreateInfo cp={VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,0,VK_COMMAND_POOL_CREATE_TRANSIENT_BIT,c->qf};CK(vkCreateCommandPool(c->d,&cp,0,&c->cp));VkDescriptorSetLayoutBinding lb[3];for(int i=0;i<3;i++)lb[i]=(VkDescriptorSetLayoutBinding){i,VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,1,VK_SHADER_STAGE_COMPUTE_BIT,0};VkDescriptorSetLayoutCreateInfo dl={VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,0,0,3,lb};CK(vkCreateDescriptorSetLayout(c->d,&dl,0,&c->dl));VkPushConstantRange pc={VK_SHADER_STAGE_COMPUTE_BIT,0,20};VkPipelineLayoutCreateInfo pl={VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,0,0,1,&c->dl,1,&pc};CK(vkCreatePipelineLayout(c->d,&pl,0,&c->pl));VkDescriptorPoolSize ps={VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,60};VkDescriptorPoolCreateInfo dp={VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,0,0,20,1,&ps};CK(vkCreateDescriptorPool(c->d,&dp,0,&c->dp));}
static VkShaderModule sm(Ctx*c,const char*path){FILE*f=fopen(path,"rb");if(!f){perror(path);exit(2);}fseek(f,0,SEEK_END);long n=ftell(f);rewind(f);uint32_t*p=malloc(n);fread(p,1,n,f);fclose(f);VkShaderModuleCreateInfo z={VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,0,0,(size_t)n,p};VkShaderModule s;CK(vkCreateShaderModule(c->d,&z,0,&s));free(p);return s;}
static void pass(Ctx*c,const char*path,Img*a,Img*b,Img*out,int ow,int oh){VkShaderModule s=sm(c,path);VkComputePipelineCreateInfo pi={VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,0,0,{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,0,0,VK_SHADER_STAGE_COMPUTE_BIT,s,"main",0},c->pl};VkPipeline p;CK(vkCreateComputePipelines(c->d,0,1,&pi,0,&p));VkDescriptorSet ds;VkDescriptorSetAllocateInfo da={VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,0,c->dp,1,&c->dl};CK(vkAllocateDescriptorSets(c->d,&da,&ds));VkDescriptorImageInfo ii[3]={{0,a->v,VK_IMAGE_LAYOUT_GENERAL},{0,(b?b:out)->v,VK_IMAGE_LAYOUT_GENERAL},{0,out->v,VK_IMAGE_LAYOUT_GENERAL}};VkWriteDescriptorSet w[3];for(int i=0;i<3;i++)w[i]=(VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,0,ds,i,0,1,VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,&ii[i],0,0};vkUpdateDescriptorSets(c->d,3,w,0,0);VkCommandBufferAllocateInfo ca={VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,0,c->cp,VK_COMMAND_BUFFER_LEVEL_PRIMARY,1};VkCommandBuffer x;CK(vkAllocateCommandBuffers(c->d,&ca,&x));VkCommandBufferBeginInfo cb={VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,0,VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,0};CK(vkBeginCommandBuffer(x,&cb));VkImageMemoryBarrier mb[3];uint32_t nm=0;Img*in[2]={a,b};for(int i=0;i<2;i++)if(in[i])mb[nm++]=(VkImageMemoryBarrier){VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,0,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_GENERAL,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED,in[i]->i,{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};mb[nm++]=(VkImageMemoryBarrier){VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,0,0,VK_ACCESS_SHADER_WRITE_BIT,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_GENERAL,VK_QUEUE_FAMILY_IGNORED,VK_QUEUE_FAMILY_IGNORED,out->i,{VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}};vkCmdPipelineBarrier(x,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,0,0,0,0,nm,mb);vkCmdBindPipeline(x,VK_PIPELINE_BIND_POINT_COMPUTE,p);vkCmdBindDescriptorSets(x,VK_PIPELINE_BIND_POINT_COMPUTE,c->pl,0,1,&ds,0,0);int pc[5]={ow,oh,0,0};vkCmdPushConstants(x,c->pl,VK_SHADER_STAGE_COMPUTE_BIT,0,20,pc);vkCmdDispatch(x,(ow+7)/8,(oh+7)/8,1);CK(vkEndCommandBuffer(x));VkSubmitInfo su={VK_STRUCTURE_TYPE_SUBMIT_INFO,0,0,0,0,1,&x,0,0};CK(vkQueueSubmit(c->q,1,&su,0));CK(vkQueueWaitIdle(c->q));vkFreeCommandBuffers(c->d,c->cp,1,&x);vkDestroyPipeline(c->d,p,0);vkDestroyShaderModule(c->d,s,0);}
static void destroy_img(Ctx*c,Img*a){vkDestroyImageView(c->d,a->v,0);vkDestroyImage(c->d,a->i,0);vkFreeMemory(c->d,a->m,0);}
/* V2 review policy intentionally changes old modes 1/3/6 to coherent ghosts.
 * Preserve their input evidence; add localized and one-sided negative controls. */
static int hn(uint16_t h){return ((h>>10)&31)==31 && (h&1023)!=0;}
static int hu(uint16_t h){return ((h>>10)&31)==31;}
static int hdist(uint16_t a,uint16_t b){
  int32_t aa=(a&0x8000)?(0x8000-(a&0x7fff)):((int32_t)a+0x8000);
  int32_t bb=(b&0x8000)?(0x8000-(b&0x7fff)):((int32_t)b+0x8000);
  int d=aa-bb; return d<0?-d:d;
}
/* NaNs compare only to NaNs; finite values use at most one half-ULP. */
static int same_h(uint16_t got,uint16_t want){
  if(hn(want)) return hn(got);
  if(hu(want)||hu(got)) return got==want;
  return hdist(got,want)<=2;
}
static int same_vec(const uint16_t *got,const uint16_t *want,int n){
  for(int i=0;i<n;i++) if(!same_h(got[i],want[i])) return 0;
  return 1;
}
static void mutation_selftests(void){
  uint16_t a[4]={hf(.125f),hf(.25f),hf(.375f),hf(.5f)},b[4]; memcpy(b,a,sizeof a);
  b[2]=hf(.5f); if(same_vec(a,b,4)){fprintf(stderr,"mutation selftest: plane mutation not rejected\n");exit(3);}
  memcpy(b,a,sizeof a); b[1]=hf(NAN); if(same_vec(a,b,4)){fprintf(stderr,"mutation selftest: invalid plane mutation not rejected\n");exit(3);}
  memcpy(b,a,sizeof a); b[3]=hf(.51f); if(same_vec(a,b,4)){fprintf(stderr,"mutation selftest: reduce mutation not rejected\n");exit(3);}
  memcpy(b,a,sizeof a); b[0]=hf(INFINITY); if(same_vec(a,b,4)){fprintf(stderr,"mutation selftest: invalid reduce mutation not rejected\n");exit(3);}
  memcpy(b,a,sizeof a); b[0]=hf(.126f); if(same_vec(a,b,4)){fprintf(stderr,"mutation selftest: merge mutation not rejected\n");exit(3);}
  fprintf(stderr,"comparator mutation selftests passed (plane/reduce/merge finite+invalid)\n");
}
static int run(Ctx*c,const char*a,const char*b,const char*m,int w,int h,int mode){
 int n=w*h,tw=(w+15)/16,th=(h+15)/16;
 uint16_t*F=calloc((size_t)n*8,2);
 int k=(mode==0||mode==9)?(n*70/100):(mode==1?n*54/100:(mode==2?n:n*70/100));
 int bad=(mode==0||mode==9)?n*35/100:(mode==10?n*26/100:(mode==1?n*35/100:n*24/100));
 if(mode==11){k=(n*56+50)/100;bad=(n*26+50)/100;}
 for(int dir=0;dir<2;dir++)for(int i=0;i<n;i++){
  int weak=i<k,badcell=i<bad;
  if(mode==9)badcell=dir?i>=n-bad:i<bad;
  if(mode==6&&dir)badcell=i<n*35/100,weak=i<n*54/100;
  if(mode==7)weak=i<n/10,badcell=0;
  if(mode==2)weak=1,badcell=0;
  if(mode==3)weak=i<n*70/100,badcell=i<n*24/100;
  if(mode>=12){int kw=n*12/100,kb=n*2/100;
   if(mode==13)kw=n*9/100;if(mode==14)kb=n*5/1000;if(mode==15&&dir)kw=n*9/100;
   if(mode==16&&dir)kb=n*5/1000;if(mode==17){kw=n*12/100;kb=n*2/100;}
   if(mode==18){kw=n*105/1000;kb=n*15/1000;}if(mode==19){kw=n*95/1000;kb=n*15/1000;}
   if(mode==20){kw=n*105/1000;kb=n*75/10000;}if(mode==22){kw=0;kb=0;}if(mode==23){kw=n;kb=n;}
   if(mode==24){kw=n;kb=0;}if(mode==25&&dir){kw=0;kb=0;}weak=i<kw;badcell=i<kb;
   if(mode==17&&dir)weak=i>=n-kw,badcell=i>=n-kb;
  }
  if(mode>=26){int kw=n*12/100;if(mode==27||mode==28)kw=n*12/100;if(mode==29)kw=n*3/100;if(mode==30)kw=n*30/100;
   weak=i<kw;badcell=0;if(mode==28&&dir)weak=0;if(mode==27){int x=i%w,y=i/w;weak=(x%6)==0&&(y%6)==0;}}
  if(mode==4&&i==0)F[(dir*n+i)*4]=hf(NAN);if(mode==5&&dir==1&&i==0)F[(dir*n+i)*4]=hf(INFINITY);
  float residual=(mode>=26&&mode!=31)?((mode==28&&dir)?0.f:(mode==27&&!((i%w)%6==0&&(i/w)%6==0)?0.f:.005f)):(badcell?.05f:0.f);
  F[(dir*n+i)*4+2]=hf(residual);F[(dir*n+i)*4+3]=hf(weak&&!badcell?0.f:.1f);
 }
 Img f=image(c,w,h),bb=image(c,w,h),t=image(c,tw,2*th),g=image(c,1,2),scene=image(c,1,1),out=image(c,1,1);
 float scene_delta=(mode==8?.25f:((mode==21||mode==23)?.19f:(mode==31?.002f:(mode==22||mode==24?0.f:.1f))));
 uint16_t sc[4]={hf(scene_delta),0,0,0};copy_to(c,&f,F,n*4*2);copy_to(c,&bb,F+n*4,n*4*2);copy_to(c,&scene,sc,8);
 pass(c,a,&f,&bb,&t,tw,th);pass(c,b,&t,0,&g,1,2);pass(c,m,&scene,&g,&out,1,1);
 uint16_t O[4],*T=calloc((size_t)tw*2*th*4,2),*G=calloc(16,2),*ET=calloc((size_t)tw*2*th*4,2),EG[8],EO[4];
 readback(c,&out,O,8);readback(c,&t,T,(size_t)tw*2*th*8);readback(c,&g,G,32);
 /* Independent first pass from half input cells. Every finite4 test covers all xyzw. */
 for(int dir=0;dir<2;dir++)for(int ty=0;ty<th;ty++)for(int tx=0;tx<tw;tx++){
  int invalid=0; float cnt[2][2]={{0}},sp[2]={0};
  for(int yy=0;yy<16;yy++)for(int xx=0;xx<16;xx++){int x=tx*16+xx,y=ty*16+yy;if(x>=w||y>=h)continue;int i=y*w+x;
   uint16_t *cell=F+(dir*n+i)*4,*other=F+((1-dir)*n+i)*4; (void)other;
   float z=fh(cell[2]),q=fh(cell[3]); int fin=1;for(int c0=0;c0<4;c0++)if(!isfinite(fh(cell[c0])))fin=0;if(!fin){invalid=1;continue;}
   cnt[0][0]+=(z>=.04f||q<=.08f);cnt[0][1]+=(z>=.04f);
   if(z>=.003f){int votes=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;uint16_t*nn=F+(dir*n+ny*w+nx)*4;int nf=1;for(int c0=0;c0<4;c0++)if(!isfinite(fh(nn[c0])))nf=0;if(nf&&fh(nn[2])>=.003f)votes++;}if(votes>=5)sp[0]+=1;}
  }
  int ti=ty*tw+tx, band=dir?tw*th:0; if(invalid){for(int c0=0;c0<4;c0++)ET[(band+ti)*4+c0]=hf(NAN);}else{ET[(band+ti)*4+0]=hf(cnt[0][0]/n);ET[(band+ti)*4+1]=0;ET[(band+ti)*4+2]=hf(cnt[0][1]/n);ET[(band+ti)*4+3]=0;}
  /* Recompute both directions in the outer dir loop; spatial is written to same band. */
  if(!invalid){float cnt0=0,cnt1=0,sp0=0;for(int yy=0;yy<16;yy++)for(int xx=0;xx<16;xx++){int x=tx*16+xx,y=ty*16+yy;if(x>=w||y>=h)continue;int i=y*w+x;uint16_t*cf=F+(i)*4,*cb=F+(n+i)*4;int ff=1,fb=1;for(int c0=0;c0<4;c0++){ff&=isfinite(fh(cf[c0]));fb&=isfinite(fh(cb[c0]));}if(ff){float z=fh(cf[2]),q=fh(cf[3]);cnt0+=(z>=.04f||q<=.08f);cnt1+=(z>=.04f);if(z>=.003f){int v=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx>=0&&ny>=0&&nx<w&&ny<h){uint16_t*nn=F+(ny*w+nx)*4;int nf=1;for(int c0=0;c0<4;c0++)nf&=isfinite(fh(nn[c0]));if(nf&&fh(nn[2])>=.003f)v++;}}if(v>=5)sp0++;}}if(fb){float z=fh(cb[2]),q=fh(cb[3]);cnt0+=(z>=.04f||q<=.08f);cnt1+=(z>=.04f);/* back support is stored below */}}
   ET[ti*4+0]=hf(cnt0/n);ET[ti*4+2]=hf(cnt1/n);ET[(tw*th+ti)*4+0]=hf(sp0/n);
  }
 }
 /* The compact independent calculation above is replaced by a clear second pass for back channels and invalid propagation. */
 for(int ti=0;ti<tw*th;ti++){int tx=ti%tw,ty=ti/tw;float c[4]={0},s[2]={0};int inv[2]={0};for(int yy=0;yy<16;yy++)for(int xx=0;xx<16;xx++){int x=tx*16+xx,y=ty*16+yy;if(x>=w||y>=h)continue;int i=y*w+x;for(int d=0;d<2;d++){uint16_t*cc=F+(d*n+i)*4;int ok=1;for(int j=0;j<4;j++)ok&=isfinite(fh(cc[j]));if(!ok){inv[d]=1;continue;}float z=fh(cc[2]),q=fh(cc[3]);c[d*2]+=(z>=.04f||q<=.08f);c[d*2+1]+=(z>=.04f);if(z>=.003f){int v=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;uint16_t*nn=F+(d*n+ny*w+nx)*4;int ok2=1;for(int j=0;j<4;j++)ok2&=isfinite(fh(nn[j]));if(ok2&&fh(nn[2])>=.003f)v++;}if(v>=5)s[d]++;}}}
  if(inv[0]||inv[1]){for(int j=0;j<4;j++)ET[ti*4+j]=hf(NAN),ET[(tw*th+ti)*4+j]=hf(NAN);}else{ET[ti*4+0]=hf(c[0]/n);ET[ti*4+1]=hf(c[2]/n);ET[ti*4+2]=hf(c[1]/n);ET[ti*4+3]=hf(c[3]/n);ET[(tw*th+ti)*4+0]=hf(s[0]/n);ET[(tw*th+ti)*4+1]=hf(s[1]/n);ET[(tw*th+ti)*4+2]=0;ET[(tw*th+ti)*4+3]=0;}}
 int plane_bad=!same_vec(T,ET,tw*2*th*4); float rs[8]={0};for(int band=0;band<2;band++)for(int y=0;y<th;y++)for(int x=0;x<tw;x++)for(int j=0;j<4;j++)rs[band*4+j]+=fh(ET[(band*tw*th+y*tw+x)*4+j]);for(int j=0;j<8;j++)EG[j]=hf(rs[j]);int red_bad=!same_vec(G,EG,8);
 float weak=fminf(fh(EG[0]),fh(EG[1])),badf=fminf(fh(EG[2]),fh(EG[3])),supported=fminf(fh(EG[4]),fh(EG[5]));int gv=1;for(int j=0;j<8;j++)gv&=isfinite(fh(EG[j]))&&fh(EG[j])>=0&&fh(EG[j])<=1.01f;int sv=1;for(int j=4;j<8;j++)sv&=isfinite(fh(EG[j]))&&fh(EG[j])>=0&&fh(EG[j])<=1.01f;int ghost=gv&&fh(sc[0])<=.1882353f&&((weak>=.10f&&badf>=.01f)||(sv&&fh(sc[0])>=.003f&&weak>=.04f&&weak<=.25f&&supported>=.008f));EO[0]=sc[0];EO[1]=hf(ghost?1.f:0.f);EO[2]=hf(gv?weak:0.f);EO[3]=hf(gv?badf:0.f);int merge_bad=!same_vec(O,EO,4);
 if(plane_bad||red_bad||merge_bad){fprintf(stderr,"geometry oracle %dx%d mode%d plane=%d reduce=%d merge=%d expected=%d got=%g\n",w,h,mode,plane_bad,red_bad,merge_bad,ghost,fh(O[1]));return 1;}
 printf("PASS %dx%d mode%d expected=%d got=%d weak=%.6f bad=%.6f support=%.6f\n",w,h,mode,ghost,(fh(O[1])>.5f),fh(G[0]),fh(G[2]),fh(G[4]));free(ET);free(G);free(T);destroy_img(c,&out);destroy_img(c,&scene);destroy_img(c,&g);destroy_img(c,&t);destroy_img(c,&bb);destroy_img(c,&f);free(F);CK(vkResetDescriptorPool(c->d,c->dp,0));return 0;
}
int main(int ac,char**av){if(ac!=4){fprintf(stderr,"usage: %s first.spv reduce.spv merge.spv\n",av[0]);return 2;}mutation_selftests();Ctx c={0};init(&c);int dims[][2]={{104,59},{128,72},{160,160},{17,19}};for(int i=0;i<4;i++)for(int k=0;k<32;k++)if(run(&c,av[1],av[2],av[3],dims[i][0],dims[i][1],k))return 1;printf("Vulkan pair-gate fixtures passed: 4 grids x 32 independent CPU-oracle controls\n");return 0;}
