#include "engine.h"
#ifdef MAX_HAS_QNN
#include <QnnInterface.h>
#include <QnnOpDef.h>
#include <HTP/QnnHtpDevice.h>
#include <HTP/QnnHtpGraph.h>
#include <dlfcn.h>
#include <vector>
#include <array>
#include <stdexcept>
#include <cstdlib>
#include <jni.h>
#include <mutex>
#include <memory>
static void check(Qnn_ErrorHandle_t code,const char* step) { if(code!=QNN_SUCCESS) throw std::runtime_error(std::string(step)+" · QNN error "+std::to_string(code)); }

// The cache belongs to the process. A mutex serializes prepare, configuration
// replacement and execution; stopping a session preserves the finalized graph.
struct QnnSession {
    void* library=nullptr;
    QNN_INTERFACE_VER_TYPE api=QNN_INTERFACE_VER_TYPE_INIT;
    Qnn_LogHandle_t logger=nullptr;
    Qnn_BackendHandle_t backend=nullptr;
    Qnn_DeviceHandle_t device=nullptr;
    Qnn_ContextHandle_t context=nullptr;
    Qnn_GraphHandle_t graph=nullptr;
    uint32_t n,layers,dims[3];
    uint32_t random=0x6d617821;
    std::string directory;
    std::vector<uint8_t> input,output;
    std::vector<std::vector<uint8_t>> weights;
    std::vector<std::string> weightNames,nodeNames,outputNames;
    Qnn_Tensor_t in=QNN_TENSOR_INIT,out=QNN_TENSOR_INIT;
    QnnSession(const std::string& dir,uint32_t size,uint32_t depth)
        : n(size),layers(depth),dims{1,size,size},directory(dir),
          input(size*size),output(size*size),weights(depth),
          weightNames(depth),nodeNames(depth),outputNames(depth) {}
    uint8_t next() { random^=random<<13; random^=random>>17; random^=random<<5; return uint8_t(112+(random%33)); }
    ~QnnSession() {
        if(context && api.contextFree) api.contextFree(context,nullptr);
        if(device && api.deviceFree) api.deviceFree(device);
        if(backend && api.backendFree) api.backendFree(backend);
        if(logger && api.logFree) api.logFree(logger);
        if(library) dlclose(library);
    }
    void initialize() {
        const auto& dir=directory;
        const std::string dspPath=dir+";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp";
        setenv("ADSP_LIBRARY_PATH",dspPath.c_str(),1);
        library=dlopen((dir+"/libQnnHtp.so").c_str(),RTLD_NOW|RTLD_LOCAL);
        if(!library) { const char* error=dlerror(); throw std::runtime_error(error?error:"libQnnHtp.so 無法載入"); }
        using GetProviders=Qnn_ErrorHandle_t(*)(const QnnInterface_t***,uint32_t*);
        auto getProviders=reinterpret_cast<GetProviders>(dlsym(library,"QnnInterface_getProviders"));
        if(!getProviders) throw std::runtime_error("找不到 QNN provider API");
        const QnnInterface_t** providers=nullptr; uint32_t count=0; check(getProviders(&providers,&count),"getProviders");
        bool found=false; for(uint32_t i=0;i<count;i++) {
            if(providers[i]->apiVersion.coreApiVersion.major==QNN_API_VERSION_MAJOR && providers[i]->apiVersion.coreApiVersion.minor>=QNN_API_VERSION_MINOR) { api=providers[i]->QNN_INTERFACE_VER_NAME; found=true; break; }
        }
        if(!found) throw std::runtime_error("QNN API 版本不相容，請使用同一套 SDK runtime");
        if(api.logCreate) api.logCreate(nullptr,QNN_LOG_LEVEL_WARN,&logger);
        check(api.backendCreate(logger,nullptr,&backend),"backendCreate HTP");
        check(api.deviceCreate(logger,nullptr,&device),"deviceCreate HTP（檢查 CDSP 驅動 / Stub / Skel）");
        check(api.contextCreate(backend,device,nullptr,&context),"contextCreate");
        QnnHtpGraph_CustomConfig_t optimization{};
        optimization.option=QNN_HTP_GRAPH_CONFIG_OPTION_OPTIMIZATION;
        optimization.optimizationOption.type=QNN_HTP_GRAPH_OPTIMIZATION_TYPE_FINALIZE_OPTIMIZATION_FLAG;
        optimization.optimizationOption.floatValue=3;
        QnnGraph_Config_t graphConfig=QNN_GRAPH_CONFIG_INIT;
        graphConfig.option=QNN_GRAPH_CONFIG_OPTION_CUSTOM; graphConfig.customConfig=&optimization;
        const QnnGraph_Config_t* configs[]={&graphConfig,nullptr};
        check(api.graphCreate(context,"max_int8_matmul",configs,&graph),"graphCreate");
        for(auto &x:input) x=next();
        auto tensor=[&](const char* name,Qnn_TensorType_t type,void* data)->Qnn_Tensor_t {
            Qnn_Tensor_t t=QNN_TENSOR_INIT; t.v1.name=name; t.v1.type=type; t.v1.dataType=QNN_DATATYPE_UFIXED_POINT_8;
            t.v1.rank=3; t.v1.dimensions=dims; t.v1.memType=QNN_TENSORMEMTYPE_RAW;
            t.v1.quantizeParams.encodingDefinition=QNN_DEFINITION_DEFINED;
            t.v1.quantizeParams.quantizationEncoding=QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
            t.v1.quantizeParams.scaleOffsetEncoding={1.f/128.f,-128};
            t.v1.clientBuf={type==QNN_TENSOR_TYPE_STATIC?data:nullptr,type==QNN_TENSOR_TYPE_STATIC?n*n:0u}; check(api.tensorCreateGraphTensor(graph,&t),name); return t;
        };
        in=tensor("input",QNN_TENSOR_TYPE_APP_WRITE,input.data()); auto previous=in;
        // Names and static buffers remain alive until finalization and execution finish.
        for(uint32_t i=0;i<layers;i++) {
            weights[i].resize(n*n); for(auto &x:weights[i]) x=next();
            weightNames[i]="weight_"+std::to_string(i); nodeNames[i]="matmul_"+std::to_string(i); outputNames[i]="activation_"+std::to_string(i);
            auto weight=tensor(weightNames[i].c_str(),QNN_TENSOR_TYPE_STATIC,weights[i].data());
            out=tensor(outputNames[i].c_str(),i==layers-1?QNN_TENSOR_TYPE_APP_READ:QNN_TENSOR_TYPE_NATIVE,i==layers-1?output.data():nullptr);
            Qnn_Tensor_t inputs[]={previous,weight};
            Qnn_OpConfig_t op=QNN_OPCONFIG_INIT; op.v1.name=nodeNames[i].c_str(); op.v1.packageName=QNN_OP_PACKAGE_NAME_QTI_AISW; op.v1.typeName=QNN_OP_MAT_MUL;
            op.v1.numOfInputs=2; op.v1.inputTensors=inputs; op.v1.numOfOutputs=1; op.v1.outputTensors=&out;
            check(api.graphAddNode(graph,op),"graphAddNode MatMul"); previous=out;
        }
        check(api.graphFinalize(graph,nullptr,nullptr),"graphFinalize HTP");
        in.v1.clientBuf={input.data(),n*n}; out.v1.clientBuf={output.data(),n*n};

    }
};
static std::mutex qnnMutex;
static std::unique_ptr<QnnSession> cached;
static std::atomic<int> preparation{0},compilations{0};
static std::atomic<long long> preparationMs{0};
static QnnSession& ensurePrepared(const std::string& dir) {
    if(cached && cached->n==workload.matrix && cached->layers==workload.layers && cached->directory==dir) {
        preparation=2; return *cached;
    }
    preparation=1;
    auto started=std::chrono::steady_clock::now();
    detail(2,"HTP 首次初始化 / 圖編譯中…");
    cached.reset();
    try {
        auto candidate=std::make_unique<QnnSession>(dir,workload.matrix,workload.layers);
        candidate->initialize();
        cached=std::move(candidate);
        compilations++;
        preparationMs=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-started).count();
        preparation=2;
        detail(2,"HTP 圖已就緒 · 進程內復用 · 編譯 "+std::to_string(compilations.load())+" 次 · "+std::to_string(preparationMs.load())+" ms");
        return *cached;
    } catch(const std::exception& e) { preparation=3; detail(2,e.what()); throw; }
}
struct PowerVote {
    QnnHtpDevice_PerfInfrastructure_t* perf=nullptr;
    uint32_t powerId=0; bool powerCreated=false;
    ~PowerVote() { if(powerCreated) perf->destroyPowerConfigId(powerId); }
    std::string request(QNN_INTERFACE_VER_TYPE& api) {
        bool voted=false,hmxVoted=false,cengVoted=false;
        QnnDevice_Infrastructure_t infrastructure=nullptr;
        if(api.deviceGetInfrastructure && api.deviceGetInfrastructure(&infrastructure)==QNN_SUCCESS && infrastructure && infrastructure->infraType==QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) {
            perf=&infrastructure->perfInfra;
            if(perf->createPowerConfigId && perf->destroyPowerConfigId && perf->setPowerConfig && perf->createPowerConfigId(0,0,&powerId)==QNN_SUCCESS) {
                powerCreated=true;
                QnnHtpPerfInfrastructure_PowerConfig_t config{};
                config.option=QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
                auto &v=config.dcvsV3Config; v={}; v.contextId=powerId;
                v.setDcvsEnable=1; v.dcvsEnable=0; v.powerMode=QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
                v.setSleepLatency=1; v.sleepLatency=40; v.setBusParams=1; v.setCoreParams=1;
                v.busVoltageCornerMin=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                v.busVoltageCornerTarget=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                v.busVoltageCornerMax=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                v.coreVoltageCornerMin=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                v.coreVoltageCornerTarget=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                v.coreVoltageCornerMax=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                const QnnHtpPerfInfrastructure_PowerConfig_t* configs[]={&config,nullptr};
                voted=perf->setPowerConfig(powerId,configs)==QNN_SUCCESS;
                if(voted) {
                    // QAIRT HTP backend guide: HMX and CENG require an active DCVS
                    // vote on the same ID. Submit separately so rejection does not
                    // discard the accepted core/bus vote on older device drivers.
                    QnnHtpPerfInfrastructure_PowerConfig_t hmx{};
                    hmx.option=QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_HMX_V2;
                    hmx.hmxV2Config.hmxPickDefault=0;
                    hmx.hmxV2Config.hmxPerfMode=QNN_HTP_PERF_INFRASTRUCTURE_CLK_PERF_HIGH;
                    hmx.hmxV2Config.hmxVoltageCornerMin=DCVS_EXP_VCORNER_TUR;
                    hmx.hmxV2Config.hmxVoltageCornerTarget=DCVS_EXP_VCORNER_TUR;
                    hmx.hmxV2Config.hmxVoltageCornerMax=DCVS_EXP_VCORNER_TUR;
                    const QnnHtpPerfInfrastructure_PowerConfig_t* hmxConfigs[]={&hmx,nullptr};
                    hmxVoted=perf->setPowerConfig(powerId,hmxConfigs)==QNN_SUCCESS;
                    QnnHtpPerfInfrastructure_PowerConfig_t ceng{};
                    ceng.option=QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_CENG;
                    ceng.cengConfig.cengPerfMode=QNN_HTP_PERF_INFRASTRUCTURE_CLK_PERF_HIGH;
                    ceng.cengConfig.cengVoltageCornerMin=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                    ceng.cengConfig.cengVoltageCornerTarget=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                    ceng.cengConfig.cengVoltageCornerMax=DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
                    const QnnHtpPerfInfrastructure_PowerConfig_t* cengConfigs[]={&ceng,nullptr};
                    cengVoted=perf->setPowerConfig(powerId,cengConfigs)==QNN_SUCCESS;
                }
            }
        }

        return std::string("DSP/Bus:")+(voted?"接受":"未接受")+" HMX:"+(hmxVoted?"接受":"未接受")+" CENG:"+(cengVoted?"接受":"未接受");
    }
};
void runQnn(const std::string& dir) {
    std::lock_guard<std::mutex> lock(qnnMutex);
    bool executionFailed=false;
    try {
        if(!active()) { states[2]=4; return; }
        auto& session=ensurePrepared(dir);
        if(!active()) { states[2]=4; return; }
        PowerVote vote;
        auto accepted=vote.request(session.api);
        detail(2,"QNN HTP · "+std::to_string(session.n)+"³ × "+std::to_string(session.layers)+" · 圖復用 / 編譯 "+std::to_string(compilations.load())+" 次 · "+accepted);
        while(active()) {
            auto error=session.api.graphExecute(session.graph,&session.in,1,&session.out,1,nullptr,nullptr);
            if(error!=QNN_SUCCESS) { executionFailed=true; check(error,"graphExecute HTP（圖已失效，下次重建）"); }
            counters[2]++; states[2]=2;
            session.input[counters[2].load()%(session.n*session.n)]=session.next();
        }
    } catch(const std::exception& e) {
        // PowerVote has already been destroyed before releasing its backend.
        if(executionFailed) { cached.reset(); preparation=3; }
        if(active()) { detail(2,e.what()); states[2]=3; }
    }
    if(states[2]!=3) states[2]=4;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_maxheater_NativeEngine_prepareQnnNative(JNIEnv* env,jclass,jstring directory) {
    const char* raw=env->GetStringUTFChars(directory,nullptr);
    std::string dir(raw); env->ReleaseStringUTFChars(directory,raw);
    std::lock_guard<std::mutex> lock(qnnMutex);
    try { ensurePrepared(dir); return true; } catch(const std::exception&) { return false; }
}
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_maxheater_NativeEngine_qnnPreparation(JNIEnv* env,jclass) {
    jlong values[]={preparation.load(),compilations.load(),preparationMs.load()};
    auto result=env->NewLongArray(3); env->SetLongArrayRegion(result,0,3,values); return result;
}
#else
#include <jni.h>
void runQnn(const std::string&) { detail(2,"此 ABI / 建置未包含 QNN HTP；需要 arm64 真機與 QAIRT SDK"); states[2]=3; }
extern "C" JNIEXPORT jboolean JNICALL Java_dev_maxheater_NativeEngine_prepareQnnNative(JNIEnv*,jclass,jstring) { return false; }
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_maxheater_NativeEngine_qnnPreparation(JNIEnv* env,jclass) {
    jlong values[]={3,0,0}; auto result=env->NewLongArray(3); env->SetLongArrayRegion(result,0,3,values); return result;
}
#endif
