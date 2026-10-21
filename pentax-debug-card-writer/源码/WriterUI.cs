using System;
using System.IO;
using System.Text;
using System.Linq;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

public class FilterAction {
    public string Id,Label;
    public FilterAction(string id,string label){Id=id;Label=label;}
    public override string ToString(){return Label;}
}

public class WriterForm:Form {
    Payload data;
    TabControl tabs=new TabControl();
    ComboBox models=new ComboBox(),variants=new ComboBox(),filterModels=new ComboBox(),actions=new ComboBox(),cards=new ComboBox();
    CheckBox fixedBox=new CheckBox();CheckBox[] seasons=new CheckBox[4];
    Button refresh=new Button(),write=new Button(),all=new Button();
    Label fileLabel=new Label(),status=new Label(),hint=new Label(),filterStatus=new Label();
    RichTextBox tutorial=new RichTextBox();bool busy=false,scanning=false,configuring=false;
    public bool FilterTab {get{return tabs.SelectedIndex==1;}}
    Model Current {get{return FilterTab?(filterModels.SelectedIndex<0?null:data.filters[filterModels.SelectedIndex]):(models.SelectedIndex<0?null:data.models[models.SelectedIndex]);}}
    string ActionId {get{return actions.SelectedItem==null?"":((FilterAction)actions.SelectedItem).Id;}}
    int Mask {get{int mask=0;for(int i=0;i<4;i++)if(seasons[i]!=null&&seasons[i].Checked)mask|=1<<i;return mask;}}
    Variant Chosen {get{
        Model m=Current;if(m==null)return null;
        if(!FilterTab)return variants.SelectedIndex<0?null:m.variants[variants.SelectedIndex];
        bool selected=ActionId=="activate"||ActionId=="clear";int mask=selected?Mask:0;
        return (m.variants??new Variant[0]).FirstOrDefault(v=>v.action==ActionId&&v.mask==mask);
    }}
    static Label LabelFor(string text){return new Label{Text=text,AutoSize=true,Anchor=AnchorStyles.Left};}
    static void Combo(ComboBox c){c.DropDownStyle=ComboBoxStyle.DropDownList;c.Dock=DockStyle.Fill;}
    static void Add(TableLayoutPanel panel,Control c,int col,int row,int span){c.Margin=new Padding(4,4,4,4);panel.Controls.Add(c,col,row);panel.SetColumnSpan(c,span);}
    public WriterForm(Payload payload){
        data=payload;Text="宾得工模写卡工具 1.3 · 工程模式 / 四季滤镜";
        ClientSize=new Size(1040,900);MinimumSize=new Size(950,830);Font=new Font("Microsoft YaHei UI",10F);
        BackColor=Color.FromArgb(246,248,251);StartPosition=FormStartPosition.CenterScreen;
        var layout=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(20,14,20,12),ColumnCount=3,RowCount=11};
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,115));layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,112));
        int[] heights={48,30,202,42,30,30,46,46,48,0,36};
        for(int i=0;i<heights.Length;i++)layout.RowStyles.Add(new RowStyle(i==9?SizeType.Percent:SizeType.Absolute,i==9?100:heights[i]));
        Controls.Add(layout);
        Add(layout,new Label{Text="宾得工程模式与四季滤镜",Font=new Font(Font.FontFamily,21,FontStyle.Bold),AutoSize=true,ForeColor=Color.FromArgb(27,43,63)},0,0,3);
        Add(layout,new Label{Text="选择功能 → 核对机型与存储卡 → 写入后按教程在相机上操作",AutoSize=true,ForeColor=Color.DimGray},0,1,3);
        tabs.Dock=DockStyle.Fill;
        var engineering=new TabPage("工程模式"){BackColor=Color.White};var filters=new TabPage("四季滤镜 · 脚本"){BackColor=Color.White};
        tabs.TabPages.Add(engineering);tabs.TabPages.Add(filters);Add(layout,tabs,0,2,3);
        var eLayout=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(12,10,12,8),ColumnCount=2,RowCount=3};
        eLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,110));eLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
        eLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,42));eLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,42));eLayout.RowStyles.Add(new RowStyle(SizeType.Percent,100));
        engineering.Controls.Add(eLayout);Combo(models);Combo(variants);
        Add(eLayout,LabelFor("相机机型"),0,0,1);Add(eLayout,models,1,0,1);Add(eLayout,LabelFor("固件 / 方案"),0,1,1);Add(eLayout,variants,1,1,1);
        Add(eLayout,new Label{Text="保留原机型教程；新增 K-3 III 彩色版、KF、K-70、KP 的固件核验入口。\nK10D 需按固件选择方案；K-3 III Monochrome 缺项，仍禁用写卡。",Dock=DockStyle.Fill,ForeColor=Color.DimGray},0,2,2);
        var fLayout=new TableLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(12,8,12,6),ColumnCount=2,RowCount=4};
        fLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,110));fLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
        fLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,38));fLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,38));fLayout.RowStyles.Add(new RowStyle(SizeType.Absolute,42));fLayout.RowStyles.Add(new RowStyle(SizeType.Percent,100));
        filters.Controls.Add(fLayout);Combo(filterModels);Combo(actions);
        Add(fLayout,LabelFor("相机机型"),0,0,1);Add(fLayout,filterModels,1,0,1);Add(fLayout,LabelFor("操作步骤"),0,1,1);Add(fLayout,actions,1,1,1);
        Add(fLayout,LabelFor("选择四季"),0,2,1);
        var seasonPanel=new FlowLayoutPanel{Dock=DockStyle.Fill,WrapContents=false};
        string[] labels={"春 · HARUBENI","夏 · KATEN","秋 · KYUSHU","冬 · FUYUNO"};
        for(int i=0;i<4;i++){
            seasons[i]=new CheckBox{Text=labels[i],Checked=true,AutoSize=true,Margin=new Padding(0,5,14,2)};
            seasons[i].CheckedChanged+=(s,e)=>{if(!configuring)ShowTutorial();};seasonPanel.Controls.Add(seasons[i]);
        }
        all.Text="全选";all.AutoSize=true;all.Height=29;all.Click+=(s,e)=>SetMask(15);seasonPanel.Controls.Add(all);Add(fLayout,seasonPanel,1,2,1);
        filterStatus.Dock=DockStyle.Fill;filterStatus.ForeColor=Color.FromArgb(32,91,120);Add(fLayout,filterStatus,0,3,2);
        Combo(cards);Add(layout,LabelFor("存储卡盘符"),0,3,1);Add(layout,cards,1,3,1);refresh.Text="刷新盘符";refresh.Dock=DockStyle.Fill;Add(layout,refresh,2,3,1);
        fixedBox.Text="显示固定磁盘型读卡器（系统盘始终排除）";fixedBox.AutoSize=true;Add(layout,fixedBox,1,4,2);
        hint.Text="插入读卡器后刷新；请核对盘符、卷标和容量。";hint.Dock=DockStyle.Fill;hint.ForeColor=Color.DimGray;Add(layout,hint,0,5,3);
        fileLabel.Dock=DockStyle.Fill;fileLabel.ForeColor=Color.FromArgb(38,56,76);Add(layout,fileLabel,0,6,3);
        var buttons=new FlowLayoutPanel{Dock=DockStyle.Fill};write.Text="写入工程模式文件";write.AutoSize=true;write.Height=34;buttons.Controls.Add(write);
        var save=new Button{Text="保存当前教程",AutoSize=true,Height=34};buttons.Controls.Add(save);var help=new Button{Text="使用说明",AutoSize=true,Height=34};buttons.Controls.Add(help);Add(layout,buttons,0,7,3);
        status.Text="1.3 · 工程入口、脚本探测与滤镜启用分别操作。";status.Dock=DockStyle.Fill;status.ForeColor=Color.FromArgb(20,90,130);Add(layout,status,0,8,3);
        tutorial.ReadOnly=true;tutorial.Dock=DockStyle.Fill;tutorial.BackColor=Color.White;tutorial.Font=Font;tutorial.DetectUrls=true;Add(layout,tutorial,0,9,3);
        Add(layout,new Label{Text="不格式化、不修改照片、不刷固件。写卡成功仅代表文件校验通过，机内效果请按教程验证。",Dock=DockStyle.Fill,ForeColor=Color.DimGray},0,10,3);
        foreach(var m in data.models)models.Items.Add(m.display+(m.kind=="pending"?"  [资料不完整]":m.kind=="button"?"  [无需文件]":""));
        foreach(var m in data.filters)filterModels.Items.Add(m.display);
        actions.Items.AddRange(new object[]{new FilterAction("entry","1 · 工程菜单入口（K33 为 Factory）"),new FilterAction("probe","2 · 只写标记探测（需先开脚本模式）"),new FilterAction("activate","3 · 启用选中四季"),new FilterAction("clear","4 · 撤销选中四季"),new FilterAction("script_enable","备选 · 仅开启脚本模式"),new FilterAction("script_disable","备选 · 关闭脚本模式")});
        models.SelectedIndexChanged+=(s,e)=>{if(models.SelectedIndex<0)return;var m=data.models[models.SelectedIndex];variants.Items.Clear();foreach(var v in m.variants)variants.Items.Add(v.label);variants.SelectedIndex=m.variants.Length==1?0:-1;variants.Enabled=m.variants.Length>0;ShowTutorial();};
        variants.SelectedIndexChanged+=(s,e)=>ShowTutorial();filterModels.SelectedIndexChanged+=(s,e)=>ShowTutorial();actions.SelectedIndexChanged+=(s,e)=>ShowTutorial();tabs.SelectedIndexChanged+=(s,e)=>ShowTutorial();
        cards.SelectedIndexChanged+=(s,e)=>UpdateWrite();refresh.Click+=(s,e)=>RefreshCards();fixedBox.CheckedChanged+=(s,e)=>RefreshCards();write.Click+=(s,e)=>WriteCard();
        save.Click+=(s,e)=>{if(Current==null)return;using(var d=new SaveFileDialog{Filter="文本文件|*.txt",FileName=Current.model+(FilterTab?"_四季滤镜教程.txt":"_工程模式教程.txt")})if(d.ShowDialog(this)==DialogResult.OK)try{File.WriteAllText(d.FileName,tutorial.Text,new UTF8Encoding(true));}catch(Exception ex){MessageBox.Show(this,ex.Message,"保存失败");}};
        help.Click+=(s,e)=>{using(var w=new Form{Text="使用说明",Size=new Size(800,650),StartPosition=FormStartPosition.CenterParent}){w.Controls.Add(new RichTextBox{Dock=DockStyle.Fill,ReadOnly=true,Font=Font,Text=data.readme});w.ShowDialog(this);}};
        tutorial.LinkClicked+=(s,e)=>{try{System.Diagnostics.Process.Start(e.LinkText);}catch{}};
        models.SelectedIndex=Array.FindIndex(data.models,m=>m.model=="K-1 II");filterModels.SelectedIndex=Array.FindIndex(data.filters,m=>m.model=="K-1 II");actions.SelectedIndex=2;
        Shown+=(s,e)=>RefreshCards();FormClosing+=(s,e)=>{if(busy){e.Cancel=true;MessageBox.Show(this,"请等待当前写卡操作结束。","正在写入");}};
    }
    void SetMask(int mask){configuring=true;for(int i=0;i<4;i++)seasons[i].Checked=(mask&(1<<i))!=0;configuring=false;ShowTutorial();}
    void ShowTutorial(){
        if(configuring||Current==null)return;Variant v=Chosen;bool selectable=FilterTab&&(ActionId=="activate"||ActionId=="clear");foreach(var c in seasons)c.Enabled=selectable;all.Enabled=selectable;
        filterStatus.Text=data.filters[filterModels.SelectedIndex<0?0:filterModels.SelectedIndex].status+"。首次使用先完成脚本开关和探测步骤。";
        write.Text=FilterTab?(ActionId=="activate"?"写入选中滤镜启用脚本":ActionId=="clear"?"写入选中滤镜撤销脚本":"写入当前步骤文件"):"写入工程模式文件";
        string selection=selectable?"选择季节："+string.Join("、",seasons.Where(c=>c.Checked).Select(c=>c.Text))+"\n未勾选的季节不会自动清除。\n\n":"";
        tutorial.Text=selection+(v==null?"":"当前方案："+(FilterTab?actions.Text:v.label)+"\n写入文件："+string.Join("、",v.files.Keys)+"\n\n")+Current.tutorial;
        fileLabel.Text=v!=null?"将写入："+string.Join("、",Engine.Decode(v).Select(p=>p.Key+"（"+p.Value.Length+" 字节）")):selectable?"请至少勾选一个季节。":Current.kind=="button"?"无需工模文件，请按下方教程操作。":Current.variants.Length>0?"请选择与固件对应的方案。":"该机型资料不完整，写卡禁用。";
        UpdateWrite();
    }
    void UpdateWrite(){write.Enabled=!busy&&!scanning&&Chosen!=null&&cards.SelectedIndex>=0;}
    void SetBusy(bool value){busy=value;tabs.Enabled=cards.Enabled=refresh.Enabled=fixedBox.Enabled=!value;UpdateWrite();}
    void RefreshCards(){
        if(busy||scanning)return;scanning=true;refresh.Enabled=fixedBox.Enabled=false;UpdateWrite();hint.Text="正在识别存储卡…";
        bool showFixed=fixedBox.Checked;string previous=cards.SelectedItem==null?null:((Card)cards.SelectedItem).Root;
        System.Threading.Tasks.Task.Factory.StartNew(()=>Engine.Scan(showFixed)).ContinueWith(task=>{
            if(IsDisposed||Disposing)return;
            BeginInvoke((Action)(()=>{
                scanning=false;refresh.Enabled=fixedBox.Enabled=true;cards.Items.Clear();var found=task.IsFaulted?new List<Card>():task.Result;
                foreach(var c in found)cards.Items.Add(c);int idx=found.FindIndex(c=>c.Root==previous);if(idx<0)idx=found.FindIndex(c=>c.Type==DriveType.Removable);if(idx>=0)cards.SelectedIndex=idx;
                hint.Text=found.Count==0?"未检测到存储卡。插入后刷新；固定磁盘型读卡器可勾选上方选项。":"按相机目录/卷标优先排列；请核对盘符与容量，机型需手动选择。";UpdateWrite();
            }));
        });
    }
    void WriteCard(){
        if(Chosen==null||cards.SelectedIndex<0||busy)return;
        Card card=(Card)cards.SelectedItem;Variant variant=Chosen;bool isFilter=FilterTab;SetBusy(true);status.Text="正在检查卡内文件…";
        System.Threading.Tasks.Task.Factory.StartNew(()=>{if(Engine.Serial(card.Root)!=card.Serial)throw new IOException("存储卡已更换，请刷新盘符。");return Engine.Prepare(card.Root,variant,data);}).ContinueWith(task=>{
            if(IsDisposed)return;BeginInvoke((Action)(()=>{
                if(task.IsFaulted){Fail(task.Exception.GetBaseException());return;}Plan plan=task.Result;var old=plan.Changes.Where(c=>c.Old!=null).ToArray();
                if(old.Length>0&&MessageBox.Show(this,"目标："+card.Root+" "+card.Label+"\n"+string.Join("\n",old.Select(c=>(c.New==null?"备份并移走：":"备份并替换：")+c.Name))+"\n\n原文件会保存在卡内 Pentax_Debug_Backup_*，保留原有相对目录。是否继续？","已有工程文件或启动脚本",MessageBoxButtons.YesNo,MessageBoxIcon.Question)!=DialogResult.Yes){SetBusy(false);status.Text="已取消，未修改文件。";return;}
                status.Text="正在写入并逐字节校验…";
                System.Threading.Tasks.Task.Factory.StartNew(()=>{if(Engine.Serial(card.Root)!=card.Serial)throw new IOException("存储卡已更换，请刷新。");return Engine.Apply(plan);}).ContinueWith(done=>{
                    if(IsDisposed)return;BeginInvoke((Action)(()=>{
                        if(done.IsFaulted){Fail(done.Exception.GetBaseException());return;}SetBusy(false);
                        status.Text="写卡成功："+card.Root+"，文件校验通过。"+(done.Result==null?"":"备份："+Path.GetFileName(done.Result)+"。")+(isFilter?"安全弹出后按教程在相机上验证；尚不代表激活成功。":"请安全弹出卡，按下方教程进入相机工程菜单。");
                        tutorial.SelectionStart=0;tutorial.ScrollToCaret();
                    }));
                });
            }));
        });
    }
    void Fail(Exception ex){SetBusy(false);status.Text="写入未完成："+ex.Message;MessageBox.Show(this,ex.Message,"写入未完成",MessageBoxButtons.OK,MessageBoxIcon.Error);}
    public void SelectK33FactoryView(){tabs.SelectedIndex=0;models.SelectedIndex=Array.FindIndex(data.models,m=>m.model=="K-3 III");variants.SelectedIndex=1;ShowTutorial();}
    public void SelectFilterView(){tabs.SelectedIndex=1;ShowTutorial();}
    public void TestUI(){
        tabs.SelectedIndex=0;
        for(int i=0;i<data.models.Length;i++){
            models.SelectedIndex=i;if(tutorial.Text.Length<30)throw new Exception("工程教程缺失");
            if((Current.kind=="pending"||Current.kind=="button")&&Chosen!=null)throw new Exception("缺项/按键机型可写卡");
            if(Current.model=="K10D"&&(Chosen!=null||variants.Items.Count!=4))throw new Exception("K10D 必须选固件");
            foreach(var v in Current.variants){variants.SelectedIndex=Array.IndexOf(Current.variants,v);if(!tutorial.Text.Contains(v.label))throw new Exception("工程方案教程缺失");}
        }
        tabs.SelectedIndex=1;
        for(int m=0;m<data.filters.Length;m++){
            filterModels.SelectedIndex=m;
            for(int a=0;a<actions.Items.Count;a++){
                actions.SelectedIndex=a;bool selected=ActionId=="activate"||ActionId=="clear";
                for(int mask=selected?1:0;mask<=(selected?15:0);mask++){
                    SetMask(mask);if(Chosen==null)throw new Exception("滤镜方案缺失");Engine.Decode(Chosen);
                    if(selected&&Chosen.mask!=mask)throw new Exception("四季勾选未生效");
                    if(!tutorial.Text.Contains(Current.status))throw new Exception("验证状态未显示");
                }
                if(selected){SetMask(0);if(Chosen!=null)throw new Exception("空选仍可写滤镜");}
            }
        }
        filterModels.SelectedIndex=1;actions.SelectedIndex=2;SetMask(15);
    }
}

public static class Program {
    [System.Runtime.InteropServices.DllImport("user32.dll")] static extern bool PrintWindow(IntPtr window,IntPtr dc,uint flags);
    [STAThread] public static int Main(string[] args){
        try{
            var data=Engine.Load();Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
            if(args.Length>=2&&args[0]=="--self-test"){
                Engine.Tests(data);WriterIntegrationTests.Run(data);using(var f=new WriterForm(data)){f.TestUI();}
                File.WriteAllText(args[1],"PASS: "+data.models.Length+" engineering model UI entries; "+data.models.Sum(m=>m.variants.Length)+" engineering plans; "+data.filters.Length+" seasonal models; "+data.filters.Sum(m=>m.variants.Length)+" staged/selection plans; all seasonal masks and empty-selection rejection; exact bytes; photo preservation; conflict backup; staged engineering-to-script and script-to-engineering transitions; unknown-script protection; nested backup; rollback; changed-card rejection; path and script whitelist. Tests used temporary directories only; no SD/camera writes.",new UTF8Encoding(true));return 0;
            }
            var form=new WriterForm(data);if(args.Contains("--filters"))form.SelectFilterView();if(args.Contains("--k33-factory"))form.SelectK33FactoryView();
            if(args.Length>=2&&args[0]=="--smoke-test"){
                var timer=new Timer{Interval=1200};timer.Tick+=(s,e)=>{timer.Stop();using(var bitmap=new Bitmap(form.Width,form.Height)){using(var graphics=Graphics.FromImage(bitmap)){IntPtr dc=graphics.GetHdc();try{if(!PrintWindow(form.Handle,dc,0))throw new IOException("界面截图失败。");}finally{graphics.ReleaseHdc(dc);}}bitmap.Save(args[1],System.Drawing.Imaging.ImageFormat.Png);}form.Close();};timer.Start();
            }
            Application.Run(form);return 0;
        }catch(Exception ex){
            if(args.Length>=2&&(args[0]=="--self-test"||args[0]=="--smoke-test"))File.WriteAllText(args[1]+(args[0]=="--smoke-test"?".error.txt":""),ex.ToString());
            else MessageBox.Show(ex.ToString(),"程序错误");return 1;
        }
    }
}
