$ErrorActionPreference = 'Stop'

# 生成 Ponder 用的结构模板（gzip 压缩的 NBT），格式与 StructureTemplate.load 一致：
# size / palette / blocks / entities
$outDir = 'D:\CreateMetro\src\main\resources\assets\createmetro\ponder'
if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }

# NBT 的 short / int 一律是大端（DataInputStream.readUnsignedShort / readInt），
# 不能用 BinaryWriter 的 Write([int16]) / Write([int32])，那会写成小端，MC 解析会错位。
function Add-Short([System.IO.BinaryWriter]$w, [int]$v) {
    $b = [System.BitConverter]::GetBytes([int16]$v)
    [Array]::Reverse($b)
    $w.Write($b)
}

function Add-Int([System.IO.BinaryWriter]$w, [int]$v) {
    $b = [System.BitConverter]::GetBytes([int32]$v)
    [Array]::Reverse($b)
    $w.Write($b)
}

function Add-TagHeader([System.IO.BinaryWriter]$w, [byte]$type, [string]$name) {
    $w.Write($type)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($name)
    Add-Short $w $bytes.Length
    if ($bytes.Length -gt 0) { $w.Write($bytes) }
}

function Add-StringTag([System.IO.BinaryWriter]$w, [string]$name, [string]$value) {
    Add-TagHeader $w 8 $name
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($value)
    Add-Short $w $bytes.Length
    if ($bytes.Length -gt 0) { $w.Write($bytes) }
}

function Add-IntTag([System.IO.BinaryWriter]$w, [string]$name, [int]$value) {
    Add-TagHeader $w 3 $name
    Add-Int $w $value
}

function Add-IntListTag([System.IO.BinaryWriter]$w, [string]$name, [int[]]$values) {
    Add-TagHeader $w 9 $name
    $w.Write([byte]3)
    Add-Int $w $values.Length
    foreach ($v in $values) { Add-Int $w $v }
}

function Add-PaletteEntry([System.IO.BinaryWriter]$w, [string]$blockName, [hashtable]$props) {
    Add-StringTag $w 'Name' $blockName
    if ($props -and $props.Count -gt 0) {
        Add-TagHeader $w 10 'Properties'
        foreach ($key in $props.Keys) { Add-StringTag $w $key ([string]$props[$key]) }
        $w.Write([byte]0)
    }
    $w.Write([byte]0)
}

function Add-BlockEntry([System.IO.BinaryWriter]$w, [int]$x, [int]$y, [int]$z, [int]$state) {
    Add-IntListTag $w 'pos' @($x, $y, $z)
    Add-IntTag $w 'state' $state
    $w.Write([byte]0)
}

function New-Schematic([string]$fileName, [int]$sizeX, [int]$sizeY, [int]$sizeZ, $palette, $blocks) {
    $path = Join-Path $outDir $fileName
    $ms = New-Object System.IO.MemoryStream
    $w = New-Object System.IO.BinaryWriter($ms)

    Add-TagHeader $w 10 ''
    Add-IntListTag $w 'size' @($sizeX, $sizeY, $sizeZ)

    Add-TagHeader $w 9 'palette'
    $w.Write([byte]10)
    Add-Int $w $palette.Count
    foreach ($entry in $palette) { Add-PaletteEntry $w $entry[0] $entry[1] }

    Add-TagHeader $w 9 'blocks'
    $w.Write([byte]10)
    Add-Int $w $blocks.Count
    foreach ($b in $blocks) { Add-BlockEntry $w $b[0] $b[1] $b[2] $b[3] }

    Add-TagHeader $w 9 'entities'
    $w.Write([byte]10)
    Add-Int $w 0

    $w.Write([byte]0)
    $w.Flush()
    $raw = $ms.ToArray()
    $w.Dispose()
    $ms.Dispose()

    $fs = [System.IO.File]::Create($path)
    $gz = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Compress)
    $gz.Write($raw, 0, $raw.Length)
    $gz.Dispose()
    $fs.Dispose()
    Write-Host "generated $fileName ($($raw.Length) bytes raw)"
}

# ---------------------------------------------------------------- 场景 A
# 9x5x9：y=0 是站台地板；x=2/5、z=4 处是门两侧的玻璃隔断；
# x=3 左向门、x=4 右向门，共同组成一对（朝向北）。
$floor = @()
for ($x = 0; $x -lt 9; $x++) {
    for ($z = 0; $z -lt 9; $z++) { $floor += ,@($x, 0, $z, 0) }
}
$glassA = @()
foreach ($x in @(2, 5)) { foreach ($y in @(1, 2)) { $glassA += ,@($x, $y, 4, 1) } }
$doorsA = @(
    @(3, 1, 4, 2), @(3, 2, 4, 3),   # 左向门 下半 / 上半
    @(4, 1, 4, 4), @(4, 2, 4, 5)    # 右向门 下半 / 上半
)
$paletteA = @()
$paletteA += ,@('minecraft:polished_andesite', @{})
$paletteA += ,@('minecraft:glass', @{})
$paletteA += ,@('createmetro:platform_screen_door_left',  @{ facing='north'; half='lower'; hinge='left';  open='false'; powered='false'; visible='true' })
$paletteA += ,@('createmetro:platform_screen_door_left',  @{ facing='north'; half='upper'; hinge='left';  open='false'; powered='false'; visible='true' })
$paletteA += ,@('createmetro:platform_screen_door_right', @{ facing='north'; half='lower'; hinge='right'; open='false'; powered='false'; visible='true' })
$paletteA += ,@('createmetro:platform_screen_door_right', @{ facing='north'; half='upper'; hinge='right'; open='false'; powered='false'; visible='true' })
New-Schematic 'platform_door_basics.nbt' 9 5 9 $paletteA ($floor + $glassA + $doorsA)

# ---------------------------------------------------------------- 场景 B
# 两组门对：x=2/3 与 x=6/7，均为 z=4；两侧玻璃隔断 x=1/4/5/8。
$glassB = @()
foreach ($x in @(1, 4, 5, 8)) { foreach ($y in @(1, 2)) { $glassB += ,@($x, $y, 4, 1) } }
$doorsB = @(
    @(2, 1, 4, 2), @(2, 2, 4, 3),
    @(3, 1, 4, 4), @(3, 2, 4, 5),
    @(6, 1, 4, 2), @(6, 2, 4, 3),
    @(7, 1, 4, 4), @(7, 2, 4, 5)
)
$paletteB = @()
$paletteB += ,@('minecraft:polished_andesite', @{})
$paletteB += ,@('minecraft:glass', @{})
$paletteB += ,@('createmetro:platform_screen_door_left',  @{ facing='north'; half='lower'; hinge='left';  open='false'; powered='false'; visible='true' })
$paletteB += ,@('createmetro:platform_screen_door_left',  @{ facing='north'; half='upper'; hinge='left';  open='false'; powered='false'; visible='true' })
$paletteB += ,@('createmetro:platform_screen_door_right', @{ facing='north'; half='lower'; hinge='right'; open='false'; powered='false'; visible='true' })
$paletteB += ,@('createmetro:platform_screen_door_right', @{ facing='north'; half='upper'; hinge='right'; open='false'; powered='false'; visible='true' })
New-Schematic 'platform_door_linker.nbt' 9 5 9 $paletteB ($floor + $glassB + $doorsB)

