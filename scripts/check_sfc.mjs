// SFC 结构扫（契约 docs/AI_ASSIST_BOUNDARY.md §9.1）：改过 .vue 先跑这一本，再跑 vue-tsc。
//
// 为什么需要它：`vite build` 只报**第一个**错、`vue-tsc` 只报**每文件第一个**错，
// 所以"构建绿"不等于"模板层验过"。这里用 `vue/compiler-sfc` 把每一只 .vue 都
// parse + compileTemplate + compileScript 过一遍，逐只报判决，一次列全。
//
// 范围要说清：这一本抓的是**结构与语法**（标签没闭合、指令表达式解析不了、script 块编不过）；
// **类型错归 `vue-tsc`**（compileScript 不做类型检查 —— 自检 K3 就是为钉住这条边界而存在的），两本都要跑。
//
// ⚠ 依赖解析：`@vue/compiler-sfc` 是**传递依赖**，工程根 import 不到（D-134 踩过）；
//   `vue/compiler-sfc` 是 vue 包自己的 subpath export，用 createRequire 指向前端包即可从任何 cwd 跑。
//
// 用法：node scripts/check_sfc.mjs <目录或文件> [...]
//       node scripts/check_sfc.mjs --self-test
// rc：0 全绿 / 1 有失败 / 2 拒判（0 候选、目录不存在、编译器 require 不到）
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FE = path.join(HERE, '..', 'yudao-ui', 'yudao-ui-admin-vue3')

let parse, compileTemplate, compileScript
try {
  const req = createRequire(path.join(FE, 'package.json'))
  ;({ parse, compileTemplate, compileScript } = req('vue/compiler-sfc'))
} catch (e) {
  console.log('✘ 拒判：从 ' + FE + ' require 不到 vue/compiler-sfc（' + e.message + '）')
  process.exit(2)
}

function checkOne(file, src) {
  const errs = []
  try {
    const { descriptor, errors } = parse(src, { filename: file })
    for (const e of errors) errs.push('parse: ' + e.message)
    if (descriptor.template) {
      const r = compileTemplate({
        source: descriptor.template.content,
        filename: file,
        id: file,
        scoped: descriptor.styles.some((s) => s.scoped)
      })
      for (const e of r.errors) errs.push('template: ' + (e.message || e))
    }
    if (descriptor.scriptSetup || descriptor.script) compileScript(descriptor, { id: file })
  } catch (e) {
    errs.push('script: ' + e.message)
  }
  return errs
}

function collect(targets) {
  const files = []
  const walk = (p) => {
    for (const e of fs.readdirSync(p, { withFileTypes: true })) {
      const f = path.join(p, e.name)
      if (e.isDirectory()) {
        if (e.name !== 'node_modules' && e.name !== 'dist' && !e.name.startsWith('dist-')) walk(f)
      } else if (f.endsWith('.vue')) files.push(f)
    }
  }
  for (const t of targets) {
    const abs = path.resolve(t)
    if (!fs.existsSync(abs)) {
      console.log('✘ 拒判：目录/文件不存在 ' + t)
      process.exit(2)
    }
    if (fs.statSync(abs).isDirectory()) walk(abs)
    else if (abs.endsWith('.vue')) files.push(abs)
  }
  return files
}

// ── 自检：判据自己得能红 ────────────────────────────────────────────────────
const GOOD = `<template><div class="a">{{ msg }}</div></template>
<script setup lang="ts">import { ref } from 'vue'
const msg = ref('ok')</script>
<style scoped>.a { color: #333 }</style>
`
const BAD_TEMPLATE = `<template><div><span>{{ msg }}</div></template>
<script setup lang="ts">import { ref } from 'vue'
const msg = ref('ok')</script>
`
const BAD_SCRIPT = `<template><div>{{ msg }}</div></template>
<script setup lang="ts">const msg = ref('ok'</script>
`

function selfTest() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sfc-knife-'))
  let bad = 0
  const knife = (name, src, wantRed) => {
    const f = path.join(dir, name)
    fs.writeFileSync(f, src, 'utf-8')
    const errs = checkOne(f, src)
    const red = errs.length > 0
    const ok = red === wantRed
    if (!ok) bad++
    console.log(
      '%s %s（期望%s，实得%d 条错%s）',
      ok ? '✔' : '✘',
      name,
      wantRed ? '红' : '绿',
      errs.length,
      errs.length ? '：' + errs[0].slice(0, 90) : ''
    )
  }
  knife('good.vue', GOOD, false)
  knife('bad-template.vue', BAD_TEMPLATE, true)
  knife('bad-script.vue', BAD_SCRIPT, true)
  fs.rmSync(dir, { recursive: true, force: true })

  // K4：0 候选必须拒判（"数到 0"报绿就是空转）
  const empty = fs.mkdtempSync(path.join(os.tmpdir(), 'sfc-empty-'))
  const files = collect([empty])
  const ok4 = files.length === 0
  console.log('%s K4 空目录数到 %d 只 .vue ⇒ 主流程必须 rc=2 拒判', ok4 ? '✔' : '✘', files.length)
  fs.rmSync(empty, { recursive: true, force: true })
  if (!ok4) bad++
  process.exit(bad ? 1 : 0)
}

if (process.argv.includes('--self-test')) selfTest()

const targets = process.argv.slice(2)
if (targets.length === 0) {
  console.log('✘ 拒判：没给扫描目标（不给目标就报绿 = 空转）')
  process.exit(2)
}
const files = collect(targets)
if (files.length === 0) {
  console.log('✘ 拒判：一只 .vue 都没扫到（0 候选不许报绿）')
  process.exit(2)
}
let bad = 0
for (const f of files) {
  const errs = checkOne(f, fs.readFileSync(f, 'utf-8'))
  if (errs.length) {
    bad++
    console.log('✘ ' + path.relative(process.cwd(), f))
    for (const e of errs.slice(0, 4)) console.log('    ' + e)
  }
}
console.log('[扫描] .vue %d 只，模板/脚本层失败 %d 只', files.length, bad)
if (bad === 0) console.log('✅ SFC 结构扫全绿（parse + compileTemplate + compileScript）')
process.exit(bad ? 1 : 0)
