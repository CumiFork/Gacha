// ESP line engine: reads Free Fire IL2CPP player positions via ptrace remote calls
// and outputs screen-space lines as JSON. Run as root.
// Build: ndk clang++ -std=c++17 -O2 -pie -fPIE -o libespline.so esp_line.cpp -llog

#include <android/log.h>
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <chrono>
#include <thread>

#define LOG_TAG "EspLine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using u64 = uint64_t;
using u32 = uint32_t;
using u8  = uint8_t;
using i64 = int64_t;
using i32 = int32_t;

struct Vec3 { float x=0,y=0,z=0; };
struct Quat { float x=0,y=0,z=0,w=1; };

static constexpr u64 TRAP_PC = 0x111111111110ULL;
static constexpr int STACK_RESERVE = 0x1000;

struct MapEntry { u64 start=0,end=0,offset=0; std::string perms,path; };

static std::vector<MapEntry> read_maps(int pid) {
    std::vector<MapEntry> out;
    char p[64]; snprintf(p,sizeof(p),"/proc/%d/maps",pid);
    std::ifstream in(p);
    std::string line;
    while (std::getline(in,line)) {
        MapEntry m{}; char perms[8]{}, dev[16]{}, pathname[512]{};
        unsigned long long s=0,e=0,off=0,inode=0;
        int n = sscanf(line.c_str(), "%llx-%llx %7s %llx %15s %llu %511[^\n]", &s,&e,perms,&off,dev,&inode,pathname);
        if (n < 6) continue;
        m.start=s; m.end=e; m.offset=off; m.perms=perms;
        if (n==7) { m.path=pathname; while(!m.path.empty() && m.path[0]==' ') m.path.erase(m.path.begin()); }
        out.push_back(m);
    }
    return out;
}

static bool pmem_read(int pid, u64 addr, void* buf, size_t len) {
    if (!addr || len==0) return false;
    struct iovec local{buf,len}, remote{(void*)addr,len};
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    if (n == (ssize_t)len) return true;
    char p[64]; snprintf(p,sizeof(p),"/proc/%d/mem",pid);
    int fd = open(p, O_RDONLY);
    if (fd < 0) return false;
    ssize_t r = pread(fd, buf, len, (off_t)addr);
    close(fd);
    return r == (ssize_t)len;
}
template<typename T> static bool read_t(int pid, u64 addr, T& out){ return pmem_read(pid,addr,&out,sizeof(T)); }
static u64 read_ptr(int pid, u64 addr){ u64 v=0; read_t(pid,addr,v); return v; }
static u32 read_u32(int pid, u64 addr){ u32 v=0; read_t(pid,addr,v); return v; }
static i32 read_i32(int pid, u64 addr){ i32 v=0; read_t(pid,addr,v); return v; }
static float read_f32(int pid, u64 addr){ float v=0; read_t(pid,addr,v); return v; }

static int find_pid(const std::string& name) {
    DIR* d = opendir("/proc"); if(!d) return -1;
    struct dirent* e; int found=-1;
    while((e=readdir(d))) {
        if(!isdigit((unsigned char)e->d_name[0])) continue;
        int pid = atoi(e->d_name);
        char cp[64]; snprintf(cp,sizeof(cp),"/proc/%d/cmdline",pid);
        std::ifstream in(cp, std::ios::binary); std::string cmd; std::getline(in,cmd,'\0');
        if(cmd.find(name)!=std::string::npos){ found=pid; break; }
    }
    closedir(d); return found;
}

static u64 elf_load_bias(int pid, const std::string& lib, u64 sampleRva) {
    auto maps = read_maps(pid);
    std::vector<MapEntry> libMaps;
    for(auto& m: maps) if(m.path.find(lib)!=std::string::npos) libMaps.push_back(m);
    if(libMaps.empty()) return 0;
    std::string libPath = libMaps[0].path;
    int fd = open(libPath.c_str(), O_RDONLY);
    if(fd < 0) return 0;
    Elf64_Ehdr eh{};
    if(read(fd,&eh,sizeof(eh))!=sizeof(eh) || memcmp(eh.e_ident,ELFMAG,SELFMAG)!=0){ close(fd); return 0; }
    std::vector<Elf64_Phdr> ph(eh.e_phnum);
    pread(fd, ph.data(), eh.e_phentsize*eh.e_phnum, eh.e_phoff);
    close(fd);
    struct Seg { u64 off,vaddr,filesz; };
    std::vector<Seg> segs;
    for(auto& p: ph) if(p.p_type==PT_LOAD) segs.push_back({p.p_offset,p.p_vaddr,p.p_filesz});
    std::vector<u64> candidates, zeroOff;
    for(auto& m: libMaps) {
        for(auto& s: segs) {
            if(m.offset >= s.off && m.offset < s.off + s.filesz) {
                u64 bias = m.start - (s.vaddr + (m.offset - s.off));
                candidates.push_back(bias);
                if(m.offset == 0) zeroOff.push_back(bias);
            }
        }
    }
    auto isExec = [&](u64 b)->bool{
        u64 addr = b + sampleRva;
        for(auto& m: maps) if(addr>=m.start && addr<m.end && m.perms.find('x')!=std::string::npos) return true;
        return false;
    };
    std::vector<u64> execCands;
    for(auto b: candidates) if(isExec(b)) execCands.push_back(b);
    if(execCands.empty()) return 0;
    for(auto b: zeroOff) if(isExec(b)) return b;
    return *std::max_element(execCands.begin(), execCands.end());
}

// ---- ptrace remote call (ARM64) ----
struct PtRegs { u64 regs[31]; u64 sp; u64 pc; u64 pstate; };
struct FpRegs { u8 data[512]; }; // 32*16 + 4+4+8

static bool ptrace_getregs(int tid, PtRegs& r) {
    struct iovec io{&r, sizeof(r)};
    return ptrace(PTRACE_GETREGSET, tid, (void*)1 /*NT_PRSTATUS*/, &io) == 0;
}
static bool ptrace_setregs(int tid, PtRegs& r) {
    struct iovec io{&r, sizeof(r)};
    return ptrace(PTRACE_SETREGSET, tid, (void*)1, &io) == 0;
}
static bool ptrace_getfp(int tid, FpRegs& f) {
    struct iovec io{&f, sizeof(f)};
    return ptrace(PTRACE_GETREGSET, tid, (void*)2 /*NT_FPREGSET*/, &io) == 0;
}
static bool ptrace_setfp(int tid, FpRegs& f) {
    struct iovec io{&f, sizeof(f)};
    return ptrace(PTRACE_SETREGSET, tid, (void*)2, &io) == 0;
}
static float fp_get_s(const FpRegs& f, int reg, int lane) {
    // vregs are 16 bytes each at offset reg*16
    u32 bits = 0;
    memcpy(&bits, f.data + reg*16 + lane*4, 4);
    float v; memcpy(&v, &bits, 4); return v;
}
static void fp_set_s(FpRegs& f, int reg, int lane, float val) {
    u32 bits; memcpy(&bits, &val, 4);
    memcpy(f.data + reg*16 + lane*4, &bits, 4);
}

static int wait_stop(int tid, int ms=3000) {
    int status=0;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    while(std::chrono::steady_clock::now() < deadline) {
        pid_t r = waitpid(tid, &status, WNOHANG);
        if(r == tid) return status;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return -1;
}

class RemoteCall {
public:
    int tid = -1;
    u64 base = 0;
    bool attached = false;
    PtRegs savedRegs{};
    FpRegs savedFp{};
    bool hasSaved = false;

    bool attach(int t) {
        tid = t;
        if(ptrace(PTRACE_ATTACH, tid, nullptr, nullptr) != 0) {
            // retry once after setenforce 0
            system("setenforce 0 >/dev/null 2>&1");
            if(ptrace(PTRACE_ATTACH, tid, nullptr, nullptr) != 0) {
                LOGE("attach failed: %s", strerror(errno));
                return false;
            }
        }
        int st = wait_stop(tid);
        if(st < 0) { LOGE("attach wait timeout"); ptrace(PTRACE_DETACH,tid,0,0); return false; }
        if(!ptrace_getregs(tid, savedRegs) || !ptrace_getfp(tid, savedFp)) {
            LOGE("save regs failed"); ptrace(PTRACE_DETACH,tid,0,0); return false;
        }
        hasSaved = true; attached = true;
        return true;
    }
    void restore() {
        if(!attached || !hasSaved) return;
        ptrace_setregs(tid, savedRegs);
        ptrace_setfp(tid, savedFp);
    }
    void detach() {
        if(!attached) return;
        restore();
        ptrace(PTRACE_DETACH, tid, nullptr, nullptr);
        attached = false; hasSaved = false;
    }

    bool call(u64 rva, const std::vector<u64>& args, u64 instance, bool hasInstance, PtRegs& outRegs, FpRegs& outFp) {
        if(!attached || !hasSaved) return false;
        PtRegs regs = savedRegs;
        FpRegs fp = savedFp;
        regs.pc = base + rva;
        regs.regs[30] = TRAP_PC;
        regs.sp = (savedRegs.sp - STACK_RESERVE) & ~0xFULL;
        int slot = 0;
        if(hasInstance) { regs.regs[slot++] = instance; }
        for(u64 a: args) { if(slot>7) return false; regs.regs[slot++] = a; }
        if(slot>7) return false;
        regs.regs[slot] = 0; // MethodInfo
        if(!ptrace_setfp(tid, fp)) return false;
        if(!ptrace_setregs(tid, regs)) return false;
        if(ptrace(PTRACE_CONT, tid, nullptr, nullptr) != 0) return false;
        while(true) {
            int st = wait_stop(tid, 3000);
            if(st < 0) return false;
            if(!WIFSTOPPED(st)) return false;
            int sig = WSTOPSIG(st);
            PtRegs cur{};
            if(!ptrace_getregs(tid, cur)) return false;
            if(cur.pc == TRAP_PC) { outRegs = cur; ptrace_getfp(tid, outFp); return true; }
            if(sig == SIGSTOP || sig == SIGCONT) { ptrace(PTRACE_CONT,tid,0,0); continue; }
            LOGE("call stopped pc=%llx sig=%d", (unsigned long long)cur.pc, sig);
            return false;
        }
    }

    u64 call_ptr(u64 rva, u64 instance, bool hasInstance=true) {
        PtRegs r{}; FpRegs f{};
        if(!call(rva, {}, instance, hasInstance, r, f)) return 0;
        return r.regs[0];
    }
    u32 call_u32(u64 rva, u64 instance, bool hasInstance=true) {
        return (u32)call_ptr(rva, instance, hasInstance);
    }
    bool call_vec3(u64 rva, u64 instance, Vec3& out) {
        PtRegs r{}; FpRegs f{};
        if(!call(rva, {}, instance, true, r, f)) return false;
        out.x = fp_get_s(f,0,0); out.y = fp_get_s(f,0,1); out.z = fp_get_s(f,1,0);
        return true;
    }
    bool call_quat(u64 rva, u64 instance, Quat& out) {
        PtRegs r{}; FpRegs f{};
        if(!call(rva, {}, instance, true, r, f)) return false;
        out.x = fp_get_s(f,0,0); out.y = fp_get_s(f,0,1); out.z = fp_get_s(f,1,0); out.w = fp_get_s(f,1,1);
        return true;
    }
};

// ---- JSON/config helpers ----
static std::string read_file(const std::string& path) {
    std::ifstream in(path); if(!in) return "";
    std::ostringstream ss; ss<<in.rdbuf(); return ss.str();
}
// Minimal JSON number/bool extraction (no deps)
static bool json_get_u64(const std::string& j, const std::string& key, u64& out) {
    auto pos = j.find("\""+key+"\"");
    if(pos==std::string::npos) return false;
    pos = j.find(':', pos); if(pos==std::string::npos) return false;
    pos++;
    while(pos<j.size() && (j[pos]==' '||j[pos]=='\t'||j[pos]=='\n'||j[pos]=='\r'||j[pos]=='\"')) pos++;
    if(pos>=j.size()) return false;
    char* end=nullptr;
    unsigned long long v = strtoull(j.c_str()+pos, &end, 0);
    if(end == j.c_str()+pos) return false;
    out = (u64)v; return true;
}
static bool json_get_bool(const std::string& j, const std::string& key, bool defv) {
    auto pos = j.find("\""+key+"\"");
    if(pos==std::string::npos) return defv;
    pos = j.find(':', pos); if(pos==std::string::npos) return defv;
    auto tail = j.substr(pos+1, 20);
    if(tail.find("true")!=std::string::npos) return true;
    if(tail.find("false")!=std::string::npos) return false;
    return defv;
}
static float json_get_float(const std::string& j, const std::string& key, float defv) {
    auto pos = j.find("\""+key+"\"");
    if(pos==std::string::npos) return defv;
    pos = j.find(':', pos); if(pos==std::string::npos) return defv;
    pos++;
    char* end=nullptr;
    float v = strtof(j.c_str()+pos, &end);
    if(end==j.c_str()+pos) return defv;
    return v;
}

struct Config {
    u64 r_curLocal=0, r_curMatch=0, r_isTeammate=0, r_getHeadTF=0, r_getPos=0;
    u64 r_mainCam=0, r_camPos=0, r_camRot=0;
    u64 f_matchPlayers=0x158, f_matchPlayersAlt=0x160;
    bool esp_enabled=true, esp_teammate_skip=true;
    float fovDeg=75.0f;
    int screenW=720, screenH=1600;
};

static Config load_config(const std::string& path) {
    Config c{};
    std::string j = read_file(path);
    if(j.empty()) return c;
    json_get_u64(j, "GameFacade.CurrentLocalPlayer", c.r_curLocal);
    json_get_u64(j, "GameFacade.CurrentMatch", c.r_curMatch);
    json_get_u64(j, "GameFacade.IsLocalTeammate", c.r_isTeammate);
    json_get_u64(j, "Player.GetHeadTF", c.r_getHeadTF);
    if(c.r_getHeadTF==0) json_get_u64(j, "Player.get_HeadBoneTransform", c.r_getHeadTF);
    json_get_u64(j, "Transform.get_position", c.r_getPos);
    json_get_u64(j, "Player.GetRuntimeMainCameraTransform", c.r_mainCam);
    json_get_u64(j, "Transform.get_rotation", c.r_camRot);
    json_get_u64(j, "match.player_list", c.f_matchPlayers);
    json_get_u64(j, "match.player_list_alt", c.f_matchPlayersAlt);
    c.esp_enabled = json_get_bool(j, "esp_enabled", true);
    c.esp_teammate_skip = json_get_bool(j, "esp_teammate_skip", true);
    c.fovDeg = json_get_float(j, "esp_fov", 75.0f);
    c.screenW = (int)json_get_float(j, "screen_w", 720);
    c.screenH = (int)json_get_float(j, "screen_h", 1600);
    return c;
}

// ---- math ----
struct Mat4 { float m[16]; }; // column-major
static Mat4 mat4_identity(){ Mat4 r{}; r.m[0]=r.m[5]=r.m[10]=r.m[15]=1; return r; }
static Mat4 mat4_mul(const Mat4& a, const Mat4& b){
    Mat4 r{};
    for(int c=0;c<4;c++) for(int row=0;row<4;row++)
        r.m[c*4+row]=a.m[0*4+row]*b.m[c*4+0]+a.m[1*4+row]*b.m[c*4+1]+a.m[2*4+row]*b.m[c*4+2]+a.m[3*4+row]*b.m[c*4+3];
    return r;
}
static Vec3 mat4_mul_vec3(const Mat4& m, const Vec3& v, float w, float& outW){
    float x=m.m[0]*v.x+m.m[4]*v.y+m.m[8]*v.z+m.m[12]*w;
    float y=m.m[1]*v.x+m.m[5]*v.y+m.m[9]*v.z+m.m[13]*w;
    float z=m.m[2]*v.x+m.m[6]*v.y+m.m[10]*v.z+m.m[14]*w;
    outW  =m.m[3]*v.x+m.m[7]*v.y+m.m[11]*v.z+m.m[15]*w;
    return {x,y,z};
}
static Mat4 quat_to_mat4(const Quat& q){
    float x=q.x,y=q.y,z=q.z,w=q.w;
    float xx=x*x,yy=y*y,zz=z*z,xy=x*y,xz=x*z,yz=y*z,wx=w*x,wy=w*y,wz=w*z;
    Mat4 r=mat4_identity();
    r.m[0]=1-2*(yy+zz); r.m[1]=2*(xy+wz);   r.m[2]=2*(xz-wy);
    r.m[4]=2*(xy-wz);   r.m[5]=1-2*(xx+zz); r.m[6]=2*(yz+wx);
    r.m[8]=2*(xz+wy);   r.m[9]=2*(yz-wx);   r.m[10]=1-2*(xx+yy);
    return r;
}
static Mat4 perspective(float fovDeg, float aspect, float nearZ, float farZ){
    float f = 1.0f / tanf((fovDeg * (float)M_PI / 180.0f) * 0.5f);
    Mat4 r{};
    r.m[0]=f/aspect; r.m[5]=f;
    r.m[10]=-(farZ+nearZ)/(farZ-nearZ);
    r.m[11]=-1.0f;
    r.m[14]=-(2.0f*farZ*nearZ)/(farZ-nearZ);
    return r;
}
static bool world_to_screen(const Vec3& world, const Vec3& camPos, const Quat& camRot, float fovDeg, int sw, int sh, float& sx, float& sy) {
    // Unity: camera forward +Z in world, view space +Z forward.
    Mat4 rot = quat_to_mat4(camRot);
    Mat4 invRot=mat4_identity(); // transpose(rotation)
    invRot.m[0]=rot.m[0]; invRot.m[1]=rot.m[4]; invRot.m[2]=rot.m[8];
    invRot.m[4]=rot.m[1]; invRot.m[5]=rot.m[5]; invRot.m[6]=rot.m[9];
    invRot.m[8]=rot.m[2]; invRot.m[9]=rot.m[6]; invRot.m[10]=rot.m[10];
    Vec3 rel{world.x-camPos.x, world.y-camPos.y, world.z-camPos.z};
    float vw;
    Vec3 view = mat4_mul_vec3(invRot, rel, 1.0f, vw); // +Z forward
    if(view.z < 0.1f) return false;
    Mat4 proj = perspective(fovDeg, (float)sw/(float)sh, 0.1f, 1000.0f);
    float cw;
    Vec3 clip = mat4_mul_vec3(proj, view, 1.0f, cw);
    if(fabsf(cw) < 1e-6f) return false;
    float ndcX = clip.x / cw;
    float ndcY = clip.y / cw;
    sx = (ndcX + 1.0f) * 0.5f * (float)sw;
    sy = (1.0f - ndcY) * 0.5f * (float)sh;
    return sx>= -200 && sx <= sw+200 && sy>= -200 && sy <= sh+200;
}

static std::vector<u64> read_il2cpp_list(int pid, u64 listPtr) {
    std::vector<u64> out;
    if(!listPtr) return out;
    u64 items = read_ptr(pid, listPtr + 0x10);
    u32 size = read_u32(pid, listPtr + 0x18);
    if(!items || size > 128) return out;
    for(u32 i=0;i<size;i++) {
        u64 p = read_ptr(pid, items + 0x20 + (u64)i*8);
        if(p) out.push_back(p);
    }
    return out;
}

static void emit_frame(int sw, int sh, const std::vector<std::pair<std::pair<float,float>,std::pair<float,float>>>& lines) {
    printf("{\"type\":\"frame\",\"w\":%d,\"h\":%d,\"lines\":[", sw, sh);
    for(size_t i=0;i<lines.size();i++) {
        auto& a = lines[i].first; auto& b = lines[i].second;
        if(i) printf(",");
        printf("{\"x1\":%.1f,\"y1\":%.1f,\"x2\":%.1f,\"y2\":%.1f}", a.first,a.second,b.first,b.second);
    }
    printf("]}\n");
    fflush(stdout);
}

int main(int argc, char** argv) {
    std::string cfgPath = "/data/local/tmp/arkana-aimlock/ff_rvas.json";
    if(argc > 1) cfgPath = argv[1];
    int screenW = 720, screenH = 1600;
    if(argc > 3) { screenW = atoi(argv[2]); screenH = atoi(argv[3]); }

    Config cfg = load_config(cfgPath);
    if(screenW>0) cfg.screenW = screenW;
    if(screenH>0) cfg.screenH = screenH;

    LOGI("ESP start cfg=%s sw=%d sh=%d", cfgPath.c_str(), cfg.screenW, cfg.screenH);
    fprintf(stderr, "[esp] cfg=%s screen=%dx%d\n", cfgPath.c_str(), cfg.screenW, cfg.screenH);

    int pid = -1;
    for(int i=0;i<50 && pid<0;i++) { pid = find_pid("com.dts.freefire"); if(pid<0){ std::this_thread::sleep_for(std::chrono::milliseconds(500)); } }
    if(pid < 0) { fprintf(stderr, "[esp] game not found\n"); return 1; }
    fprintf(stderr, "[esp] pid=%d\n", pid);

    u64 base = elf_load_bias(pid, "libil2cpp.so", cfg.r_curLocal ? cfg.r_curLocal : 0x71BD10C);
    if(!base) { fprintf(stderr, "[esp] libil2cpp base not found\n"); return 1; }
    fprintf(stderr, "[esp] base=%llx\n", (unsigned long long)base);

    int frameCount = 0;
    while(true) {
        std::vector<std::pair<std::pair<float,float>,std::pair<float,float>>> lines;
        int np = find_pid("com.dts.freefire");
        if(np < 0) { emit_frame(cfg.screenW,cfg.screenH,lines); std::this_thread::sleep_for(std::chrono::milliseconds(1000)); continue; }
        if(np != pid) { pid=np; base=elf_load_bias(pid,"libil2cpp.so",cfg.r_curLocal?cfg.r_curLocal:0x71BD10C); fprintf(stderr,"[esp] pid=%d base=%llx\n",pid,(unsigned long long)base); }
        if(!base) { emit_frame(cfg.screenW,cfg.screenH,lines); std::this_thread::sleep_for(std::chrono::milliseconds(1000)); continue; }

        RemoteCall rc; rc.base = base;
        if(!rc.attach(pid)) { emit_frame(cfg.screenW,cfg.screenH,lines); std::this_thread::sleep_for(std::chrono::milliseconds(500)); continue; }

        u64 local = cfg.r_curLocal ? rc.call_ptr(cfg.r_curLocal, 0, false) : 0;
        u64 match = cfg.r_curMatch ? rc.call_ptr(cfg.r_curMatch, 0, false) : 0;
        if(local && match) {
            u64 camTf = cfg.r_mainCam ? rc.call_ptr(cfg.r_mainCam, local, true) : 0;
            Vec3 camPos{}; Quat camRot{};
            bool okPos=false, okRot=false;
            if(camTf && cfg.r_getPos) okPos = rc.call_vec3(cfg.r_getPos, camTf, camPos);
            if(camTf && cfg.r_camRot) okRot = rc.call_quat(cfg.r_camRot, camTf, camRot);
            if(okPos && okRot) {
                std::vector<u64> players;
                auto la = read_il2cpp_list(pid, match + cfg.f_matchPlayers);
                auto lb = read_il2cpp_list(pid, match + cfg.f_matchPlayersAlt);
                players.insert(players.end(), la.begin(), la.end());
                players.insert(players.end(), lb.begin(), lb.end());
                std::sort(players.begin(), players.end());
                players.erase(std::unique(players.begin(), players.end()), players.end());
                for(u64 p: players) {
                    if(!p || p==local) continue;
                    if(cfg.esp_teammate_skip && cfg.r_isTeammate) {
                        PtRegs tr{}; FpRegs tf{};
                        if(rc.call(cfg.r_isTeammate, {p}, 0, false, tr, tf)) {
                            if(((u32)tr.regs[0]) != 0) continue;
                        }
                    }
                    u64 headTf = cfg.r_getHeadTF ? rc.call_ptr(cfg.r_getHeadTF, p, true) : 0;
                    if(!headTf) continue;
                    Vec3 head{};
                    if(!rc.call_vec3(cfg.r_getPos, headTf, head)) continue;
                    float sx,sy;
                    if(world_to_screen(head, camPos, camRot, cfg.fovDeg, cfg.screenW, cfg.screenH, sx, sy)) {
                        lines.push_back({{(float)cfg.screenW*0.5f, (float)cfg.screenH}, {sx,sy}});
                    }
                }
            }
        }

        rc.detach();
        emit_frame(cfg.screenW, cfg.screenH, lines);
        frameCount++;
        if(frameCount % 30 == 0) fprintf(stderr, "[esp] frame=%d lines=%zu\n", frameCount, lines.size());
        std::this_thread::sleep_for(std::chrono::milliseconds(80)); // ~12fps, attach/detach per frame
    }
    return 0;
}
