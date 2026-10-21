$ErrorActionPreference = 'Stop'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) {
    $compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
}
$sourcePath = Join-Path $PSScriptRoot 'PentaxWriter.cs'
$payloadPath = Join-Path $PSScriptRoot 'payload.json'
$outputPath = Join-Path (Split-Path -Parent $PSScriptRoot) '宾得工模写卡工具.exe'
$compilerArgs = @('/nologo', '/target:winexe', '/platform:anycpu', '/optimize+',
    ('/out:' + $outputPath), '/reference:System.Windows.Forms.dll',
    '/reference:System.Drawing.dll', '/reference:System.Web.Extensions.dll',
    ('/resource:' + $payloadPath + ',payload.json'), $sourcePath,
    (Join-Path $PSScriptRoot 'WriterUI.cs'), (Join-Path $PSScriptRoot 'WriterIntegrationTests.cs'))
& $compiler @compilerArgs
if ($LASTEXITCODE -ne 0) { throw '编译失败。' }
Write-Output ('已生成：' + $outputPath)
