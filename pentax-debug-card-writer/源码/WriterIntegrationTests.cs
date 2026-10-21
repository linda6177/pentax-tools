using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Collections.Generic;

public static class WriterIntegrationTests {
    static Variant Find(Model m,string action,int mask){return m.variants.First(v=>v.action==action&&v.mask==mask);}
    static void Reject(Action action,string message){try{action();}catch(IOException){return;}throw new Exception(message);}
    public static void Run(Payload data){
        string root=Path.Combine(Path.GetTempPath(),"PentaxWriterTest_Integration_"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            foreach(var model in data.filters){
                string card=Path.Combine(root,model.model.Replace(' ','_'));Directory.CreateDirectory(card);
                string photo=Path.Combine(card,"DCIM","100PENTX","original.dng");Directory.CreateDirectory(Path.GetDirectoryName(photo));byte[] image=Enumerable.Range(0,256).Select(x=>(byte)x).ToArray();File.WriteAllBytes(photo,image);
                var entry=Find(model,"entry",0);
                if(model.model=="K-3 III"){
                    string command=Encoding.ASCII.GetString(Convert.FromBase64String(entry.files["00078420.582"]));
                    if(command!="[OPEN_FACTORY_DEBUG_MENU]\r\n")throw new Exception("K33 四季入口未指向 Factory");
                    var service=data.models.First(m=>m.model=="K-3 III").variants.First(v=>v.label.StartsWith("Service Menu"));
                    Engine.Apply(Engine.Prepare(card,service,data));
                }
                Engine.Apply(Engine.Prepare(card,entry,data));
                string trigger=entry.files.Keys.First(n=>n!="DEVELOP.MOD");
                var probe=Find(model,"probe",0);string entryBackup=Engine.Apply(Engine.Prepare(card,probe,data));
                if(entryBackup==null||File.Exists(Engine.SafePath(card,trigger))||File.Exists(Engine.SafePath(card,"DEVELOP.MOD")))throw new Exception("工程入口未在脚本阶段移走");
                if(!Engine.Equal(Engine.Read(Engine.SafePath(entryBackup,trigger)),Convert.FromBase64String(entry.files[trigger])))throw new Exception("工程入口备份缺失");
                var activate=Find(model,"activate",15);Engine.Apply(Engine.Prepare(card,activate,data));
                byte[] active=Engine.Read(Engine.SafePath(card,"script/startup.ttl"));
                Reject(()=>Engine.Apply(Engine.Prepare(card,entry,data),1),"跨目录失败未触发");
                if(!Engine.Equal(active,Engine.Read(Engine.SafePath(card,"script/startup.ttl")))||File.Exists(Engine.SafePath(card,trigger)))throw new Exception("跨目录回滚未还原");
                string backup=Engine.Apply(Engine.Prepare(card,entry,data));
                if(backup==null||File.Exists(Engine.SafePath(card,"script/startup.ttl"))||!Engine.Equal(active,Engine.Read(Engine.SafePath(backup,"script/startup.ttl"))))throw new Exception("嵌套脚本备份/移走失败");
                string shutdown=Path.Combine(card,"script","shutdown.ttl");File.WriteAllText(shutdown,"keep-original-shutdown");
                Engine.Apply(Engine.Prepare(card,activate,data));
                if(File.ReadAllText(shutdown)!="keep-original-shutdown"||!Engine.Equal(File.ReadAllBytes(photo),image))throw new Exception("其他脚本或照片被修改");
                var before=Engine.Prepare(card,activate,data);File.WriteAllBytes(Engine.SafePath(card,trigger),Convert.FromBase64String(entry.files[trigger]));
                Reject(()=>Engine.Apply(before),"新增已知触发文件未被检测");File.Delete(Engine.SafePath(card,trigger));
                File.WriteAllText(Engine.SafePath(card,"script/startup.ttl"),"unknown user script");
                Reject(()=>Engine.Prepare(card,entry,data),"未知脚本被自动移走");
                string customBackup=Engine.Apply(Engine.Prepare(card,activate,data));
                if(customBackup==null||File.ReadAllText(Engine.SafePath(customBackup,"script/startup.ttl"))!="unknown user script")throw new Exception("替换未知同名脚本未留备份");
                foreach(string action in new[]{"activate","clear"})for(int mask=1;mask<16;mask++){
                    string script=Encoding.ASCII.GetString(Engine.Decode(Find(model,action,mask)).Values.Single());
                    string[] commands=script.Split(new[]{"\r\n"},StringSplitOptions.RemoveEmptyEntries).Where(line=>line.StartsWith("sendln ")).ToArray();
                    int count=Enumerable.Range(0,4).Count(i=>(mask&(1<<i))!=0);
                    if(commands.Length!=count||commands.Any(line=>action=="activate"?line.Contains("ClearForced"):!line.Contains("ClearForced")))throw new Exception("选择季节/启用撤销映射错误");
                    if(model.model=="K-3 III"&&commands.Any(line=>line.Contains("ActivationSpring")||line.Contains("ActivationAutumn")||line.Contains("ActivationWinter")))throw new Exception("K33 混入旧命令名");
                }
            }
            var invalid=new Variant{files=new Dictionary<string,string>{{"../DCIM/photo.jpg",Convert.ToBase64String(new byte[0])}}};
            Reject(()=>Engine.Decode(invalid),"路径穿越未拦截");
            Reject(()=>Engine.ValidateScript(Encoding.ASCII.GetBytes("fileopen h 'C:\\script\\X.txt' 0\r\nsendln 'rm flash'\r\nfileclose h\r\nend\r\n")),"危险脚本未拦截");
            Reject(()=>Engine.ValidateScript(Encoding.UTF8.GetPreamble().Concat(Encoding.ASCII.GetBytes("end\r\n")).ToArray()),"BOM 未拦截");
            string unknown=Path.Combine(root,"unknown");Directory.CreateDirectory(unknown);File.WriteAllText(Path.Combine(unknown,"99999999.999"),"unknown");
            Reject(()=>Engine.Prepare(unknown,Find(data.filters[0],"probe",0),data),"未知型号入口未拦截");
            string occupied=Path.Combine(root,"occupied");Directory.CreateDirectory(occupied);File.WriteAllText(Path.Combine(occupied,"script"),"a file occupies the script folder");
            Reject(()=>Engine.Apply(Engine.Prepare(occupied,Find(data.filters[0],"probe",0),data)),"script 同名文件未拦截");
        }finally{
            string full=Path.GetFullPath(root),temp=Path.GetFullPath(Path.GetTempPath()).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar;
            if(!full.StartsWith(temp,StringComparison.OrdinalIgnoreCase)||!Path.GetFileName(full).StartsWith("PentaxWriterTest_Integration_"))throw new Exception("测试目录越界");
            Directory.Delete(full,true);
        }
    }
}
