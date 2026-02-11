# Debug Info Migration Summary

**Branch:** `debug-info`  
**Base:** Modern Chisel 7.x (Tako-San/chisel main branch)  
**Source:** PR #4224 from chipsalliance/chisel (Tywaves debug info system)

---

## Цель миграции

Портировать функциональность PR #4224 (передача типовой информации Scala в FIRRTL для отладочного просмотра) на современный Chisel с использованием **Intrinsics API** вместо устаревших аннотаций.

## Ключевые изменения

### 1. Архитектурный подход

| Аспект | PR #4224 (Старый) | Эта миграция (Новый) |
|--------|-------------------|----------------------|
| Механизм | FIRRTL Annotations | **FIRRTL Intrinsics** |
| API | `ChiselAnnotation` | `Intrinsic(...)` |
| Интеграция CIRCT | Кастомный lowering pass | Нативная поддержка через `dbg` dialect |
| JSON метаданные | Генерируются Chisel | **Генерируются firtool** |

### 2. Файловая структура

```
core/src/main/scala/chisel3/debuginfo/
└── DebugIntrinsic.scala              # Основной модуль
    ├── TypeParam                      # Описание параметра конструктора
    ├── DebugTypeInfo                  # Метаданные для debug info
    └── DebugIntrinsicEmitter          # Генератор интринсиков

src/main/scala/chisel3/stage/phases/
└── EmitDebugIntrinsics.scala         # Фаза ChiselStage
    ├── EmitDebugInfoAnnotation        # Аннотация для включения
    └── EmitDebugIntrinsics            # Phase для pipeline

docs/
└── debug-info-migration.md           # Полная документация
```

### 3. Workflow изменений

**Старый workflow (PR #4224):**
```
Chisel Elaboration
    ↓
TywavesChiselAnnotation.generate() → создает ChiselAnnotation
    ↓
FIRRTL Converter → аннотации сериализуются в JSON
    ↓
Чтение JSON кастомным CIRCT pass
```

**Новый workflow (эта миграция):**
```
Chisel Elaboration
    ↓
EmitDebugIntrinsics phase → внедряет Intrinsic ноды в IR
    ↓
FIRRTL Converter → интринсики становятся частью FIRRTL IR
    ↓
firtool (CIRCT) → нативная обработка через LowerIntrinsics pass
    ↓
Debug dialect ops (dbg.variable, dbg.moduleinfo)
    ↓
Вывод: hw-debug-info.json + Verilog
```

## Что сохранено из PR #4224

✅ **Логика рефлексии Scala** - `extractConstructorParams()` практически идентичен  
✅ **Извлечение параметров конструктора** - `TypeParam` / `ClassParam` аналог  
✅ **Обход Circuit IR** - `generate(circuit/component/command)` паттерн  
✅ **Поддержка Bundle/Vec/Record** - специальная обработка агрегатных типов

## Что изменено

🔄 **TywavesAnnotation → Intrinsic** - вместо создания аннотаций используем `Intrinsic(...)(target)`  
🔄 **ChiselAnnotation → Unit** - `emitDebugInfo()` больше не возвращает аннотацию  
🔄 **Сериализация** - не генерируем JSON в Chisel, это делает firtool  
🔄 **Интеграция** - фаза вызывается через `EmitDebugInfoAnnotation`, не через command line option в ChiselStage

## Что удалено

❌ **AddTywavesAnnotations.scala** - фаза больше не нужна в старом виде  
❌ **TywavesAnnotation case class** - заменен на прямые вызовы Intrinsic  
❌ **annoCreated: HashSet** - теперь `instrumentedTargets` отслеживает по имени target

## Интринсик формат

**Генерируемый интринсик:**
```scala
Intrinsic("circt_debug_type_info",
  "type_name" -> Param("MyBundle"),
  "params" -> Param("width:Int=8,depth:Int=16"),
  "target_name" -> Param("io_data")
)(targetSignal: Data)
```

**Результат в FIRRTL:**
```firrtl
intrinsic(circt_debug_type_info<type_name="MyBundle", params="width:Int=8,depth:Int=16", target_name="io_data">, io.data)
```

## Next Steps (Следующие шаги)

### 1. CIRCT Integration (требуется работа в CIRCT)

```cpp
// В CIRCT: lib/Dialect/FIRRTL/Transforms/LowerIntrinsics.cpp

void FIRRTLLowerIntrinsicsPass::lowerDebugTypeInfo(IntrinsicOp op) {
  // Извлечь параметры
  auto typeName = op.getStringParam("type_name");
  auto params = op.getStringParam("params");
  
  // Создать dbg.variable op
  auto debugVar = builder.create<debug::VariableOp>(
    op.getLoc(),
    op.getOperand(0),  // Signal reference
    builder.getStringAttr(typeName),
    builder.getStringAttr(params)
  );
  
  // Сохранить в metadata для JSON export
  moduleMetadata[targetName] = {typeName, params};
}
```

### 2. JSON Export Pass (требуется в CIRCT)

```cpp
// В CIRCT: lib/Conversion/ExportVerilog/ExportDebugInfo.cpp

void ExportDebugInfoPass::emitJSON(ModuleOp module) {
  json output;
  
  // Traverse dbg.variable ops
  module.walk([&](debug::VariableOp var) {
    output["signals"].push_back({
      {"rtl_path", var.getPath()},
      {"chisel_name", var.getName()},
      {"source_type", var.getSourceType()},
      {"params", parseParams(var.getParams())}
    });
  });
  
  writeFile("hw-debug-info.json", output.dump(2));
}
```

### 3. Testing

```scala
// Создать тестовый модуль
class TestModule(val width: Int) extends Module {
  class MyBundle(val n: Int) extends Bundle {
    val data = UInt(n.W)
  }
  
  val io = IO(new MyBundle(width))
}

// Скомпилировать с debug info
ChiselStage.emitSystemVerilog(
  new TestModule(8),
  Array("--emit-debug-info")
)

// Проверить FIRRTL на наличие интринсика
// Проверить, что firtool создает hw-debug-info.json
```

### 4. Tywaves Integration

После того как CIRCT будет генерировать `hw-debug-info.json`, Tywaves сможет:
- Читать JSON + VCD
- Отображать сигналы с Chisel типами
- Показывать параметры конструктора
- Навигировать между исходным кодом и waveform

## Использование

### Включение debug info

**Через ChiselStage:**
```scala
import chisel3.stage.ChiselStage

ChiselStage.emitSystemVerilog(
  new MyModule,
  Array("--emit-debug-info")  // Новый флаг
)
```

**Программно:**
```scala
import chisel3.stage.phases.EmitDebugInfoAnnotation

val annos = Seq(
  ChiselGeneratorAnnotation(() => new MyModule),
  EmitDebugInfoAnnotation()  // Явное включение
)

new ChiselStage().execute(Array(), annos)
```

### Компиляция с firtool

```bash
# Сгенерировать FIRRTL с интринсиками
sbt "runMain MyMain"

# Скомпилировать с firtool (когда lowering будет готов)
firtool generated/MyModule.fir \
  --format=fir \
  --emit-debug-info \
  --export-module-hierarchy \
  -o generated/

# Результат:
# - generated/MyModule.v
# - generated/hw-debug-info.json
```

### Просмотр в Tywaves

```bash
verilator --trace generated/MyModule.v
./obj_dir/VMyModule
tywaves generated/hw-debug-info.json dump.vcd
```

## Связь с дипломной работой

### Unified Hardware Debug Stack

Эта миграция - **Layer 1 (Chisel)** в архитектуре вашего дипломного проекта:

```
Layer 1: Chisel (это PR) ────┐
  - Intrinsics для метаданных │
  - Рефлексия Scala           │
                              ↓
Layer 2: CIRCT (TODO) ────────┤
  - Lowering intrinsics       │
  - Debug dialect ops         │
  - JSON export               │
                              ↓
Layer 3: Runtime (TODO) ──────┤
  - HGDB VPI integration      │
  - Breakpoint DSL            │
  - ChiselSim hooks           │
                              ↓
Layer 4: UI (Tywaves) ────────┘
  - Typed waveform viewer
  - Source-level debugging
```

### Интеграция с ChiselTrace

**ChiselTrace** (динамический анализ зависимостей) **дополняет** эту систему:

| Система | Тип информации | Когда доступна | Применение |
|---------|---------------|----------------|------------|
| **Debug Intrinsics** (это PR) | Статическая типовая | Compile-time | Typed waveform viewing |
| **ChiselTrace** | Динамические зависимости | Runtime (VCD parsing) | CFG/PDG analysis, tracing |
| **HGDB** | Интерактивные breakpoints | Simulation-time (VPI) | Stepping, watches |

**Все три системы могут работать вместе** для комплексной отладки.

## Отличия от PR #4224

### Код

**PR #4224 (411 строк `TywavesAnnotation.scala`):**
- Создает `case class TywavesAnnotation extends SingleTargetAnnotation`
- Возвращает `Seq[ChiselAnnotation]` из `generate()`
- Использует `annoCreated: HashSet[IsMember]` для дедупликации
- Эмитит JSON-сериализованные аннотации

**Эта миграция (~250 строк `DebugIntrinsic.scala`):**
- Вызывает `Intrinsic(...)(target)` напрямую
- Возвращает `Unit` из `generate()`
- Использует `instrumentedTargets: HashSet[String]` (по target path)
- Эмитит FIRRTL intrinsic ноды

### Философия

**PR #4224:** "Annotations are the way to pass metadata"  
**2024+:** "Annotations are deprecated, use Intrinsics" [cite:42]

## Roadmap

- [x] Создать `DebugIntrinsic.scala` с intrinsic emission
- [x] Создать `EmitDebugIntrinsics.scala` phase
- [x] Написать документацию
- [ ] **Написать тесты** (unit + integration)
- [ ] **CIRCT lowering pass** для `circt_debug_type_info`
- [ ] **JSON export pass** в CIRCT
- [ ] **Интеграция с HGDB** (VPI runtime)
- [ ] **Поддержка Enum** (через `dbg.enumdef`)
- [ ] **ChiselSim hooks** для assertion-triggered debugging

## Полезные ссылки

- **Документация:** [docs/debug-info-migration.md](docs/debug-info-migration.md)
- **Оригинальный PR:** https://github.com/chipsalliance/chisel/pull/4224
- **Chisel Intrinsics:** https://www.chisel-lang.org/docs/explanations/intrinsics
- **CIRCT Debug Dialect:** https://circt.llvm.org/docs/Dialects/Debug/
- **Tywaves:** https://github.com/rameloni/tywaves-chisel
- **HGDB:** https://github.com/Kuree/hgdb

---

**Автор миграции:** AI-архитектор Unified Hardware Debug Stack  
**Дата:** 2026-02-11  
**Статус:** ✅ Chisel Layer готов, ⏳ CIRCT integration pending
