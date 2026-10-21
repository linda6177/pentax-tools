using System;
using System.IO;
using System.Text;
using System.Linq;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using System.Reflection;
using System.Runtime.InteropServices;

public class Variant { public string label; public string action; public int mask; public Dictionary<string,string> files; }
public class Model { public string model; public string display; public string kind; public string status; public string tutorial; public Variant[] variants; }
public class Payload { public string readme; public Model[] models; public Model[] filters; public string[] legacyScripts; }
public class Card { public string Root, Label, Reason; public long Total; public int Score; public uint Serial; public DriveType Type; public override string ToString(){return Root+"  "+Label+" | "+(Total/1073741824.0).ToString("F1")+" GB | "+Reason;} }
public class Change { public string Name; public byte[] Old, New; }
public class Plan { public string Root; public Dictionary<string,byte[]> Selected; public Dictionary<string,byte[]> Observed=new Dictionary<string,byte[]>(StringComparer.OrdinalIgnoreCase); public List<Change> Changes=new List<Change>(); }

public static class Engine {
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern bool GetVolumeInformation(string root,StringBuilder name,int size,out uint serial,out uint max,out uint flags,StringBuilder fs,int fsSize);
    public static uint Serial(string root){uint s,m,f;if(!GetVolumeInformation(root,new StringBuilder(261),261,out s,out m,out f,new StringBuilder(261),261))throw new IOException("存储卡无法访问，请刷新盘符。");return s;}
    public static Payload Load(){using(var stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.json"))using(var reader=new StreamReader(stream,Encoding.UTF8)){var j=new JavaScriptSerializer();j.MaxJsonLength=4000000;return j.Deserialize<Payload>(reader.ReadToEnd());}}
    public static bool Equal(byte[] a,byte[] b){return a==null?b==null:b!=null&&a.SequenceEqual(b);}
    public static IEnumerable<Model> AllModels(Payload data){return data.models.Concat(data.filters??new Model[0]);}
    public static bool IsScript(string name){return name.Replace('\\','/').Equals("script/startup.ttl",StringComparison.OrdinalIgnoreCase);}
    public static string Relative(string name){return name.Replace('/',Path.DirectorySeparatorChar).Replace('\\',Path.DirectorySeparatorChar);}
    public static string SafePath(string root,string name){
        string normalized=name.Replace('\\','/');
        if(!IsScript(name)&&!System.Text.RegularExpressions.Regex.IsMatch(normalized,@"^(MODSET(\.[A-Za-z0-9]+)?|DEVELOP\.MOD|\d{8}\.\d{3})$",System.Text.RegularExpressions.RegexOptions.IgnoreCase))throw new IOException("文件名不在允许范围："+name);
        string fullRoot=Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar;
        string path=Path.GetFullPath(Path.Combine(fullRoot,Relative(name)));
        if(!path.StartsWith(fullRoot,StringComparison.OrdinalIgnoreCase))throw new IOException("目标路径超出存储卡。");
        string current=Path.GetDirectoryName(path);
        while(current!=null&&current.Length>=fullRoot.TrimEnd(Path.DirectorySeparatorChar).Length){
            if(Directory.Exists(current)&&(File.GetAttributes(current)&FileAttributes.ReparsePoint)!=0)throw new IOException("目标目录是链接，停止写入："+current);
            current=Path.GetDirectoryName(current);
        }return path;
    }
    public static void ValidateScript(byte[] b){
        if(b.Length>4096||b.Any(x=>x>127)||b.Length==0)throw new IOException("脚本编码/长度错误。");
        string script=Encoding.ASCII.GetString(b);
        if(script.Replace("\r\n","").Contains("\n")||script.Replace("\r\n","").Contains("\r"))throw new IOException("脚本必须使用 CRLF 换行。");
        var lines=script.Split(new[]{"\r\n"},StringSplitOptions.RemoveEmptyEntries);
        if(lines.Length<4||lines[lines.Length-1]!="end")throw new IOException("脚本缺少结束语句。");
        foreach(string line in lines){
            bool ok=line=="end"||line=="fileclose h"||System.Text.RegularExpressions.Regex.IsMatch(line,@"^fileopen h 'C:\\script\\[A-Z0-9_]+\.txt' 0$")||System.Text.RegularExpressions.Regex.IsMatch(line,@"^filewriteln h '[A-Z0-9_]+'$")||System.Text.RegularExpressions.Regex.IsMatch(line,@"^sendln 'app_debug CustomImage (ForcedActivation|ClearForcedActivation)(Spring|Harubeni|Katen|Autumn|Kyushu|Winter|Fuyuno)'$");
            if(!ok)throw new IOException("脚本含未允许的指令。");
        }
    }
    public static Dictionary<string,byte[]> Decode(Variant v){
        if(v==null||v.files==null||v.files.Count==0)throw new IOException("该机型没有可写入的完整方案。");
        var result=new Dictionary<string,byte[]>(StringComparer.OrdinalIgnoreCase);
        foreach(var pair in v.files){
            SafePath(Path.GetTempPath(),pair.Key);
            byte[] b=Convert.FromBase64String(pair.Value);
            if(IsScript(pair.Key))ValidateScript(b);
            else if(pair.Key.Equals("DEVELOP.MOD",StringComparison.OrdinalIgnoreCase)){
                if(!Equal(b,new byte[0])&&!Equal(b,new byte[]{7,1,44,31,16,3,30,22,5,45}))throw new IOException("二进制内容校验失败。");
            }else{
                string cmd=Encoding.ASCII.GetString(b).Replace("\r\n","\n");
                if(cmd=="[OPEN_FACTORY_DEBUG_MENU]\n"&&!pair.Key.Equals("00078420.582",StringComparison.OrdinalIgnoreCase))throw new IOException("Factory 指令仅允许用于本次核验的 K-3 III 文件。");
                if(cmd!="[OPEN_FACTORY_DEBUG_MENU]\n"&&cmd!="[OPEN_DEBUG_MENU]\n"&&cmd!="[SCRIPT_EN_MODE EN]\n"&&cmd!="[SCRIPT_EN_MODE DIS]\n")throw new IOException("命令内容校验失败。");
            }result.Add(Relative(pair.Key),b);
        }return result;
    }
    public static byte[] Read(string path){
        if(Directory.Exists(path))throw new IOException("工模文件位置被文件夹占用："+Path.GetFileName(path));
        if(!File.Exists(path))return null;
        if((File.GetAttributes(path)&FileAttributes.ReparsePoint)!=0)throw new IOException("目标文件是链接，停止写入。");
        if(new FileInfo(path).Length>1048576)throw new IOException("同名文件大于1MB，停止写入："+Path.GetFileName(path));
        return File.ReadAllBytes(path);
    }
    public static Plan Prepare(string root,Variant variant,Payload data){
        if(!Directory.Exists(root)||(File.GetAttributes(root)&FileAttributes.ReparsePoint)!=0)throw new IOException("目标卡不存在或不是普通目录。");
        var p=new Plan{Root=root,Selected=Decode(variant)};
        foreach(var pair in p.Selected){var old=Read(SafePath(root,pair.Key));if(!Equal(old,pair.Value))p.Changes.Add(new Change{Name=pair.Key,Old=old,New=pair.Value});}
        var known=new Dictionary<string,List<byte[]>>(StringComparer.OrdinalIgnoreCase);
        foreach(var model in AllModels(data))foreach(var v in model.variants??new Variant[0])foreach(var pair in Decode(v)){
            if(!known.ContainsKey(pair.Key))known[pair.Key]=new List<byte[]>();known[pair.Key].Add(pair.Value);
        }
        string startup=Relative("script/startup.ttl");
        if(!known.ContainsKey(startup))known[startup]=new List<byte[]>();
        foreach(string script in data.legacyScripts??new string[0]){byte[] bytes=Convert.FromBase64String(script);ValidateScript(bytes);known[startup].Add(bytes);}
        foreach(string path in Directory.GetFiles(root)){
            string name=Path.GetFileName(path);
            if(System.Text.RegularExpressions.Regex.IsMatch(name,@"^(MODSET(\.[A-Za-z0-9]+)?|\d{8}\.\d{3})$",System.Text.RegularExpressions.RegexOptions.IgnoreCase)&&!known.ContainsKey(name)&&!p.Selected.ContainsKey(name))throw new IOException("发现未识别的工程触发文件："+name+"。请先核对并手动移走。");
        }
        foreach(var pair in known){
            if(p.Selected.ContainsKey(pair.Key))continue;
            var old=Read(SafePath(root,pair.Key));if(old==null)continue;
            if(!pair.Value.Any(b=>Equal(b,old)))throw new IOException("其他工模文件或启动脚本内容未知："+pair.Key+"。请核对后手动移走，避免混用。");
            p.Changes.Add(new Change{Name=pair.Key,Old=old,New=null});
        }
        foreach(string name in known.Keys.Concat(p.Selected.Keys).Distinct(StringComparer.OrdinalIgnoreCase))p.Observed[name]=Read(SafePath(root,name));
        return p;
    }
    static void WriteAtomic(string path,byte[] data){
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        string temp=Path.Combine(Path.GetDirectoryName(path),".pentax_"+Guid.NewGuid().ToString("N")+".tmp");
        try{using(var s=new FileStream(temp,FileMode.CreateNew,FileAccess.Write,FileShare.None)){s.Write(data,0,data.Length);s.Flush(true);}
            if(File.Exists(path)){
                string swap=Path.Combine(Path.GetDirectoryName(path),".pentax_old_"+Guid.NewGuid().ToString("N")+".tmp");
                File.Move(path,swap);
                try{File.Move(temp,path);}catch{File.Move(swap,path);throw;}
                try{File.Delete(swap);}catch{}
            }else File.Move(temp,path);
        }finally{if(File.Exists(temp))File.Delete(temp);}
    }
    public static string Apply(Plan p,int simulateFailureAfter=-1){
        foreach(var item in p.Observed)if(!Equal(Read(SafePath(p.Root,item.Key)),item.Value))throw new IOException("卡内工程文件或脚本已改变，请重新写入。");
        foreach(string path in Directory.GetFiles(p.Root)){
            string name=Path.GetFileName(path);
            if(System.Text.RegularExpressions.Regex.IsMatch(name,@"^(MODSET(\.[A-Za-z0-9]+)?|\d{8}\.\d{3})$",System.Text.RegularExpressions.RegexOptions.IgnoreCase)&&!p.Observed.ContainsKey(name))throw new IOException("卡内新增了工程触发文件，请重新检查。");
        }
        foreach(var c in p.Changes)if(!Equal(Read(SafePath(p.Root,c.Name)),c.Old))throw new IOException("卡内文件已改变，请重新写入。");
        foreach(var item in p.Selected)if(!p.Changes.Any(c=>c.Name==item.Key)&&!Equal(Read(SafePath(p.Root,item.Key)),item.Value))throw new IOException("卡内文件已改变，请重新写入。");
        if(new DriveInfo(Path.GetPathRoot(Path.GetFullPath(p.Root))).AvailableFreeSpace<65536)throw new IOException("目标卡空间不足。");
        string backup=null;
        if(p.Changes.Any(c=>c.Old!=null)){
            backup=Path.Combine(p.Root,"Pentax_Debug_Backup_"+DateTime.Now.ToString("yyyyMMdd_HHmmss_fff")+"_"+Guid.NewGuid().ToString("N").Substring(0,6));Directory.CreateDirectory(backup);
            foreach(var c in p.Changes.Where(c=>c.Old!=null)){string original=SafePath(backup,c.Name);Directory.CreateDirectory(Path.GetDirectoryName(original));File.WriteAllBytes(original,c.Old);}
            File.WriteAllText(Path.Combine(backup,"README.txt"),"本目录保存被替换或移走的原文件，保留相对目录。需要恢复时才把内容复制到卡根目录；script/startup.ttl 应恢复到 script 文件夹。",new UTF8Encoding(true));
        }
        var done=new List<Change>();
        try{
            foreach(var c in p.Changes){
                if(done.Count==simulateFailureAfter)throw new IOException("模拟写入失败");
                var path=SafePath(p.Root,c.Name);if(c.New==null)File.Delete(path);else WriteAtomic(path,c.New);done.Add(c);
            }
            foreach(var pair in p.Selected)if(!Equal(Read(SafePath(p.Root,pair.Key)),pair.Value))throw new IOException("写入后内容校验失败。");
            foreach(var c in p.Changes.Where(c=>c.New==null))if(File.Exists(SafePath(p.Root,c.Name)))throw new IOException("旧工模文件未移走。");
        }catch(Exception e){
            var failed=new List<string>();done.Reverse();foreach(var c in done)try{var path=SafePath(p.Root,c.Name);if(c.Old==null)File.Delete(path);else WriteAtomic(path,c.Old);}catch{failed.Add(c.Name);}
            if(failed.Count>0)throw new IOException("写入失败，恢复失败："+string.Join(", ",failed)+"。原文件备份："+backup,e);throw;
        }return backup;
    }
    public static List<Card> Scan(bool fixedDisks){
        var result=new List<Card>();string system=Path.GetPathRoot(Environment.GetFolderPath(Environment.SpecialFolder.Windows));
        foreach(var d in DriveInfo.GetDrives())try{
            if(string.Equals(d.Name,system,StringComparison.OrdinalIgnoreCase)||!d.IsReady)continue;
            if(d.DriveType!=DriveType.Removable&&!(fixedDisks&&d.DriveType==DriveType.Fixed))continue;
            bool dcim=Directory.Exists(Path.Combine(d.Name,"DCIM"));bool pentax=dcim&&Directory.GetDirectories(Path.Combine(d.Name,"DCIM")).Any(p=>System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(p),@"^\d{3}PENTX$",System.Text.RegularExpressions.RegexOptions.IgnoreCase));
            bool brand=d.VolumeLabel.ToUpperInvariant().Contains("PENTAX")||d.VolumeLabel.ToUpperInvariant().Contains("RICOH");
            result.Add(new Card{Root=d.Name,Label=d.VolumeLabel.Length==0?"未命名":d.VolumeLabel,Type=d.DriveType,Total=d.TotalSize,Serial=Serial(d.Name),Score=(d.DriveType==DriveType.Removable?100:0)+(pentax?40:dcim?20:0)+(brand?30:0),Reason=pentax?"宾得照片目录":brand?"相机命名卷标":dcim?"DCIM 照片目录":d.DriveType==DriveType.Removable?"可移动磁盘":"固定磁盘/读卡器"});
        }catch{}return result.OrderByDescending(c=>c.Score).ThenBy(c=>c.Root).ToList();
    }
    public static void Tests(Payload data){
        string root=Path.Combine(Path.GetTempPath(),"PentaxWriterTest_"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        try{
            int count=0;
            foreach(var m in AllModels(data))foreach(var v in m.variants??new Variant[0]){
                var path=Path.Combine(root,(count++).ToString());Directory.CreateDirectory(path);string photo=Path.Combine(path,"DCIM","100PENTX","photo.jpg");Directory.CreateDirectory(Path.GetDirectoryName(photo));File.WriteAllText(photo,"keep-photo");
                Apply(Prepare(path,v,data));if(File.ReadAllText(photo)!="keep-photo"||Prepare(path,v,data).Changes.Count!=0)throw new Exception("文件方案/照片保留测试失败");
            }
            var k3=data.models.First(m=>m.model=="K-3").variants[0];var ks=data.models.First(m=>m.model=="K-S1").variants[0];
            string card=Path.Combine(root,"switch");Directory.CreateDirectory(card);Apply(Prepare(card,k3,data));var before=Directory.GetFiles(card).ToDictionary(Path.GetFileName,File.ReadAllBytes);
            try{Apply(Prepare(card,ks,data),1);throw new Exception("预期模拟失败");}catch(IOException){}
            if(before.Any(p=>!Equal(p.Value,Read(Path.Combine(card,p.Key)))))throw new Exception("恢复测试失败");
            string backup=Apply(Prepare(card,ks,data));if(backup==null||File.Exists(Path.Combine(card,"00077760.502"))||Read(Path.Combine(card,"DEVELOP.MOD")).Length!=10)throw new Exception("切换/备份测试失败");
            File.WriteAllText(Path.Combine(card,"MODSET.445"),"unknown");try{Prepare(card,k3,data);throw new Exception("未知工模文件未拦截");}catch(IOException){}
            string race=Path.Combine(root,"race");Directory.CreateDirectory(race);var plan=Prepare(race,k3,data);File.WriteAllText(Path.Combine(race,"DEVELOP.MOD"),"changed");try{Apply(plan);throw new Exception("变更未拦截");}catch(IOException){}
        }finally{
            string actual=Path.GetFullPath(root),tempRoot=Path.GetFullPath(Path.GetTempPath()).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar;
            if(!actual.StartsWith(tempRoot,StringComparison.OrdinalIgnoreCase)||!Path.GetFileName(actual).StartsWith("PentaxWriterTest_"))throw new IOException("拒绝清理测试目录之外的路径。");
            Directory.Delete(actual,true);
        }
    }
}

