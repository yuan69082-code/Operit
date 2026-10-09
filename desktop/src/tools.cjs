const path = require('node:path');
const { createProcessService } = require('../../examples/windows_control/resources/pc_agent/operit-pc-agent/src/services/process-service.js');
const { createFileService } = require('../../examples/windows_control/resources/pc_agent/operit-pc-agent/src/services/file-service.js');

const definitions = [
  ['pc_command', '在当前Windows电脑运行PowerShell命令，操作应用、浏览器或系统。', { command: { type: 'string' } }, ['command']],
  ['pc_read_file', '读取当前电脑上的UTF-8文件。', { path: { type: 'string' } }, ['path']],
  ['pc_write_file', '写入当前电脑的UTF-8文件。', { path: { type: 'string' }, content: { type: 'string' } }, ['path', 'content']],
  ['pc_list_files', '列出当前电脑目录。', { path: { type: 'string' } }, ['path']],
  ['pc_screen', '截取当前Windows桌面；后续模型请求会包含截图。', {}, []],
  ['pc_click', '点击当前电脑屏幕的绝对像素坐标。', { x: { type: 'integer' }, y: { type: 'integer' } }, ['x', 'y']],
  ['pc_keys', '发送Windows SendKeys键序列，例如{ENTER}、^l；文本输入也可使用此工具。', { keys: { type: 'string' } }, ['keys']],
].map(([name, description, properties, required]) => ({ type: 'function', function: {
  name, description, parameters: { type: 'object', properties, required, additionalProperties: false }
} }));

function createTools(root, logger = console) {
  const processService = createProcessService({ projectRoot: root, logger: { info() {}, warn() {}, error: (...args) => logger.error(...args) } });
  const files = createFileService({ projectRoot: root });
  async function powershell(command) {
    const result = await processService.runCommand('powershell', command, 30000);
    if (result.exitCode !== 0) throw new Error(result.stderr || 'Windows操作失败');
    return result;
  }
  return {
    definitions,
    async execute(name, args) {
      if (process.platform !== 'win32') throw new Error('电脑控制工具需要在Windows运行');
      if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error('工具参数必须为对象');
      const definition = definitions.find(item => item.function.name === name);
      if (!definition) throw new Error('未知电脑工具：' + name);
      for (const key of definition.function.parameters.required) {
        const type = definition.function.parameters.properties[key].type;
        if (type === 'string' && typeof args[key] !== 'string') throw new Error('参数必须为文字：' + key);
      }
      if (name === 'pc_command') return powershell(args.command);
      if (name === 'pc_read_file') return files.readTextFile(args.path, 'utf8');
      if (name === 'pc_write_file') return files.writeTextFile(args.path, args.content, 'utf8');
      if (name === 'pc_list_files') return files.listDirectory(args.path, 1);
      if (name === 'pc_screen') {
        const result = await powershell("Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; $b=[Windows.Forms.SystemInformation]::VirtualScreen; $i=New-Object Drawing.Bitmap $b.Width,$b.Height; $g=[Drawing.Graphics]::FromImage($i); try {$g.CopyFromScreen($b.X,$b.Y,0,0,$i.Size); $m=New-Object IO.MemoryStream; try {$i.Save($m,[Drawing.Imaging.ImageFormat]::Png); @{image=[Convert]::ToBase64String($m.ToArray()); origin_x=$b.X; origin_y=$b.Y; width=$b.Width; height=$b.Height} | ConvertTo-Json -Compress} finally {$m.Dispose()}} finally {$g.Dispose(); $i.Dispose()}");
        const capture = JSON.parse(result.stdout.trim());
        return { ...capture, image: 'data:image/png;base64,' + capture.image, device: 'pc', time: Date.now() };
      }
      if (name === 'pc_click') {
        if (!Number.isInteger(args.x) || !Number.isInteger(args.y)) throw new Error('点击坐标必须为整数');
        return powershell(`Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class OperitMouse { [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y); [DllImport("user32.dll")] public static extern void mouse_event(uint f,uint x,uint y,uint d,UIntPtr e); }'; [OperitMouse]::SetCursorPos(${args.x},${args.y}); [OperitMouse]::mouse_event(2,0,0,0,[UIntPtr]::Zero); [OperitMouse]::mouse_event(4,0,0,0,[UIntPtr]::Zero)`);
      }
      if (name === 'pc_keys') {
        const quoted = args.keys.replaceAll("'", "''");
        return powershell(`Add-Type -AssemblyName System.Windows.Forms; [Windows.Forms.SendKeys]::SendWait('${quoted}')`);
      }
      throw new Error('未知电脑工具：' + name);
    }
  };
}
module.exports = { createTools, definitions };
