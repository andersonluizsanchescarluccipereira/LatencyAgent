# Instrumentando Latência na JVM com Byte Buddy: do Zero ao Prod (Java 8→21)

> **Tempo de leitura:** ~30–40 minutos  
> **Código-fonte analisado:** `br.com.ads.LatencyAgent` (Java Agent leve para perfil de latência por método)

Este artigo é um guia prático **end-to-end** para quem deseja **medir e entender a latência por método** dentro de aplicações Java **sem alterar o código da aplicação**. Vamos construir (e usar) um **Java Agent** baseado em **Byte Buddy** — com filtros por pacote, **modo `verbose`**, **limiar mínimo** (`minMicros`), **ranking Top‑N**, e **exportação em JSON** para observabilidade.

Além do passo a passo, você verá **quando** usar, **como interpretar** os números, e **quais armadilhas evitar** em ambientes de produção. No fim, analisamos um **exemplo real de saída** gerado pelo agente e traçamos perspectivas de evolução (Prometheus, percentis, amostragem).

---

## Tabela de Conteúdos

1. [Por que medir latência por método?](#por-que-medir-latência-por-método)
2. [Arquitetura em alto nível](#arquitetura-em-alto-nível)
3. [Byte Buddy + Java Agent: como a mágica acontece](#byte-buddy--java-agent-como-a-mágica-acontece)
4. [O agente `LatencyAgent` bloco a bloco](#o-agente-latencyagent-bloco-a-bloco)
   - [Config (parsing de `agentArgs`)](#1-config-parsing-de-agentargs)
   - [Stats (coleta rápida e thread‑safe)](#2-stats-coleta-rápida-e-threadsafe)
   - [TimingAdvice (injeção de medição)](#3-timingadvice-injeção-de-medição)
   - [premain (orquestração + Byte Buddy)](#4-premain-orquestração--byte-buddy)
5. [Como empacotar e executar](#como-empacotar-e-executar)
6. [Parâmetros e cenários de uso](#parâmetros-e-cenários-de-uso)
7. [Entendendo a saída (console + JSON)](#entendendo-a-saída-console--json)
8. [Leituras de caso: quando usar, o que observar](#leituras-de-caso-quando-usar-o-que-observar)
9. [Boas práticas e anti‑padrões em produção](#boas-práticas-e-antipadrões-em-produção)
10. [Observabilidade: integrando com Prometheus/Datadog/ELK](#observabilidade-integrando-com-prometheusdatadogelk)
11. [Troubleshooting (FAQ)](#troubleshooting-faq)
12. [Roadmap (evoluções)](#roadmap-evoluções)
13. [Resumo executivo (TL;DR)](#resumo-executivo-tldr)

---

## Por que medir latência por método?

Porque **tempo é a métrica que o usuário enxerga**. Logs ajudam, APM ajuda, mas há momentos em que você precisa de **granularidade de método**, seja para:

- **Investigar hot paths**: quem consome mais tempo acumulado?
- **Comparar abordagens** (ex.: cache vs. sem cache, stream vs. loop).
- **Apoiar decisões de refatoração** (ex.: quebrar um método enorme).
- **Validar otimizações** (ex.: trocar a estrutura de dados).

E tudo isso **sem tocar no código da aplicação**. É aqui que o **Java Agent** entra: ele injeta instruções no bytecode **em tempo de carga**, medindo entrada/saída de métodos com custo marginal.

---

## Arquitetura em alto nível

```
JVM start
  └─ -javaagent:...  → carrega LatencyAgent (Premain-Class)
         └─ Configura filtros, mínimos, top, verbose, saída JSON
         └─ Byte Buddy intercepta métodos concretos (isMethod && !constructor && !abstract)
               └─ Advice @OnMethodEnter: marca t0
               └─ Advice @OnMethodExit : mede Δt e registra em Stats
JVM shutdown
  └─ Hook imprime ranking Top-N e exporta JSON
```

Pontos-chave:
- **Sem alterar o código da app**.
- Coleta em **`ConcurrentHashMap` + `LongAdder`** (alto throughput).
- **`minMicros`** filtra ruído.
- **`include`/`exclude`** focam somente no seu domínio (evita instrumentar JDK/frameworks).
- **JSON** pronto para sidecars/colectors.

---

## Byte Buddy + Java Agent: como a mágica acontece

- **Java Agent**: um JAR com `Premain-Class` no MANIFEST. Quando a JVM inicia com `-javaagent`, ela chama `premain(String args, Instrumentation inst)`.
- **Byte Buddy**: DSL elegante para definir **quais classes/métodos** serão transformados e **qual Advice** será injetado.
- **Advice**: anotações `@OnMethodEnter` e `@OnMethodExit` para executar lógicas **antes** e **depois** do método original.

Benefícios:
- Sem mexer em classe de produção.
- Controle fino via filtros.
- Baixo acoplamento com a app instrumentada.

---

## O agente `LatencyAgent` bloco a bloco

A seguir, destrinchamos cada parte do código (resumo; o código completo está no seu repositório/projeto).

### 1) `Config` (parsing de `agentArgs`)

Responsável por interpretar os argumentos passados pela VM:

```txt
-javaagent:agent.jar=include=org.app,com.foo;exclude=org.app.dto;minMicros=500;top=20;verbose=true;output=/tmp/latency.json
```

- **`include`**: lista de prefixos de pacote a instrumentar (vazio = instrumenta tudo, exceto JDK).
- **`exclude`**: prefixos a ignorar.
- **`minMicros`**: descarta métodos abaixo de X microssegundos (reduz ruído).
- **`top`**: quantos métodos mostrar no ranking e exportar em JSON.
- **`verbose`**: se `true`, loga cada classe **instrumentada**/**transformada** (útil para debug).
- **`output`**: caminho do arquivo JSON.

**Quando usar**: ambientes de **teste/homolog** com `verbose=true` e `minMicros=0` (para validar escopo); em **produção**, ajuste `include` e `minMicros` para reduzir overhead e volume.

---

### 2) `Stats` (coleta rápida e thread‑safe)

- Armazena estatísticas por **assinatura de método** (`String` → `Item`).
- Cada `Item` usa **`LongAdder`** para `calls` e `totalNanos` (melhor sob contenda que `AtomicLong`).
- Mantém **`maxNanos`** por método.
- Em `dump()`:
  - Ordena por **tempo total acumulado** (hot paths).
  - Imprime **calls / avg(ms) / total(ms) / max(ms)**.
  - Exporta **JSON** para `output`.

**Quando usar**: sempre. É o coração do agente. O JSON permite **pós‑processamento** (dashboards, alarmes, relatórios).

---

### 3) `TimingAdvice` (injeção de medição)

- `@OnMethodEnter`: captura `System.nanoTime()` (alta resolução).
- `@OnMethodExit`: computa `Δt` e chama `Stats.record(method, dur)`.
- Projetado para **mínimo overhead** (apenas um par de leituras de clock + atualização em `LongAdder`).

**Quando usar**: automaticamente aplicado aos métodos concretos que passarem no filtro. Não mexa aqui a menos que queira lógica adicional (ex.: amostragem, p95, tags).

---

### 4) `premain` (orquestração + Byte Buddy)

- Constrói o **matcher** de tipos com `include`/`exclude`.
- Usa `new AgentBuilder.Default()` e `.type(...).transform(...)` para aplicar `Advice` a cada método.
- Listener opcional em `verbose` para logar **Instrumentando/Transformado**.
- Registra hook de shutdown para **impressão + JSON**.

**Quando usar**: sempre — é o ponto de entrada do agente. Se quiser adicionar **prometheus exporter**, é aqui que subirá um mini servidor HTTP.

---

## Como empacotar e executar

**Manifest necessário** (`maven-jar-plugin` ou `shade-plugin`):

```
Premain-Class: br.com.ads.LatencyAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

**Build**:
```bash
mvn clean package
# target/agent-1.0-SNAPSHOT.jar
```

**Execução (terminal)**:
```bash
java -javaagent:/abs/path/agent-1.0-SNAPSHOT.jar=include=org.app;minMicros=500;top=20;verbose=false;output=/var/log/latency.json \
     -jar app.jar
```

**Execução (IntelliJ)**: em *Run → Edit Configurations → VM options* (idêntico).

---

## Parâmetros e cenários de uso

### `include`
- **Dev/homolog**: comece **sem** para validar (ou use `include` amplo).
- **Prod**: sempre **defina** `include` do seu domínio (ex.: `include=com.seubanco.core,com.seubanco.api`).

### `exclude`
- Ignore DTOs, packages de infra, ou módulos que você sabe que quer deixar de fora (ex.: `exclude=com.seubanco.dto`).

### `minMicros`
- `0`: mede tudo (bom para debug; barulhento).
- `100–1000`: bons valores iniciais para prod (0,1–1 ms).  
  Use `500` como ponto de partida.

### `top`
- 10–50: suficiente para relatórios focados.
- Acima disso: só se for exportar para processamento externo.

### `verbose`
- `true`: **somente** em dev/homolog — muito log!  
- `false`: produção.

### `output`
- Produção: diretório de logs padrão (`/var/log/...`) ou volume persistente (Kubernetes).

---

## Entendendo a saída (console + JSON)

### Cabeçalho
```
=== MÉTODOS MAIS LENTOS (por tempo total acumulado) ===
calls     avg(ms)   total(ms)     max(ms)       method
```

- **`calls`**: número de invocações agregadas
- **`avg(ms)`**: média por chamada (ms)
- **`total(ms)`**: tempo total acumulado (ms) — **criterio de ordenação**
- **`max(ms)`**: maior duração observada (ms)
- **`method`**: assinatura completa

### Como priorizar
1. **`total(ms)`** alto → *hot path* cumulativo (onde otimizações rendem mais).
2. Se `avg(ms)` alto e `calls` baixo → prováveis **blocos de latência** (I/O, locks).
3. Se `max(ms)` muito acima da média → **outliers** (picos ocasionais).

### JSON
- Mesmos campos, fácil de importar em ELK, Loki, Datadog, BigQuery.
- Útil para histórico e dashboards.

---

## Leituras de caso: quando usar, o que observar

### 1) Boot lento / inicialização fria
- Rode o app com `minMicros=0`, `verbose=true` (apenas uma vez).
- Veja quais componentes dominam o **tempo total** durante o boot.

### 2) Endpoint específico com queixas de latência
- Ligue o agente com `include` só no pacote do endpoint e serviços adjacentes.
- Compare o **`total(ms)`** antes e depois da otimização.

### 3) Falhas intermitentes de performance
- Foque em **`max(ms)`** — valores muito acima da média sinalizam GC, I/O remoto lento, lock contention.

### 4) Regressão após deploy
- Rode o agente nos dois builds, salve JSON, **diff**. Procure métodos com **aumento de `avg(ms)`**.

---

## Boas práticas e antipadrões em produção

**Boas práticas**
- Sempre use `include` específico.
- Suba `minMicros` para reduzir ruído (ex.: 300–1000 µs).
- Use `top` conservador (ex.: 20–50).
- Direcione `output` para um local coletável.
- Execute em **janelas controladas** (ex.: feature flag ou por tempo).

**Evite**
- `verbose=true` em prod.
- Instrumentar frameworks/libraries sem motivo.
- Guardar o mapa estatístico por períodos muito longos sem rotação.
- Concluir que “tudo é CPU” sem descartar I/O, alocação, GC, lock contention.

---

## Observabilidade: integrando com Prometheus/Datadog/ELK

### Hoje (com JSON)
- **Filebeat/Fluent Bit/Vector** capturam o arquivo `output`.
- Envie para **ELK**/**Loki**/**Datadog Logs**.
- Construa um dashboard: `total_ms` (TOP), `avg_ms` e `max_ms` por método.

### Amanhã (roadmap)
- Exportador HTTP embutido: `/metrics` com *counters* e *histograms* (Prometheus).
- Percentis (p50/p95/p99) com HdrHistogram/TDigest.

---

## Troubleshooting (FAQ)

**“Failed to find Premain-Class manifest attribute”**  
→ Empacotamento incorreto. Gere MANIFEST com `Premain-Class: br.com.ads.LatencyAgent`.

**“Could not determine current working directory”**  
→ `Working directory` inválido. Ajuste no terminal/IntelliJ.

**Ranking vazio**  
→ Filtro `include` errado **ou** `minMicros` muito alto **ou** método `transform` retornando `null`.  
Valide com `verbose=true` e `minMicros=0`.

**`IllegalAccessError`**  
→ Classes internas não eram públicas. Já corrigido.

---

## Roadmap (evoluções)

- Exportador **Prometheus/OpenMetrics** (HTTP)
- **Percentis** (p50/p95/p99) por método
- **Amostragem** (sample rate) para overhead ainda menor
- Agregação por **classe/pacote**
- **Flush periódico** de JSON
- **Rotação e limite** do mapa (bound memory)
- Filtros **regex** por método
- **CSV** e **NDJSON** como formatos alternativos
- **Attach em runtime** (`agentmain`) sem restart da JVM

---

## Resumo executivo (TL;DR)

- **LatencyAgent** entrega **visão por método** **sem tocar no código** da app.  
- Controle **o escopo** (`include`) e **o ruído** (`minMicros`).  
- Use `verbose=true` apenas em **diagnóstico**.  
- Priorize por **tempo total** (`total_ms`) e investigue **outliers** (`max_ms`).  
- Exporte o **JSON** e coloque sob observabilidade.  
- Roadmap: Prometheus + percentis + amostragem.

---

# Apêndice — Interpretando sua saída real

Você obteve o seguinte ranking:

```
=== MÉTODOS MAIS LENTOS (por tempo total acumulado) ===
calls     avg(ms)   total(ms)     max(ms)       method
1         90186,289  90186,289     90186,289     public static void org.anderson.jvm.Main.main(java.lang.String[]) throws java.lang.InterruptedException
10        0,911     9,109         2,392         public void org.anderson.jvm.escapereference.maapratica.CarroCollection.printAllCarros()
110       0,013     1,425         0,039         public java.lang.String org.anderson.jvm.escapereference.maapratica.Preco.toString()
20        0,015     0,299         0,070         public org.anderson.jvm.escapereference.maapratica.Carro org.anderson.jvm.escapereference.maapratica.CarroCollection.findCarroByModelo(java.lang.String)
20        0,006     0,118         0,024         public java.lang.Double org.anderson.jvm.escapereference.maapratica.Preco.converter(java.lang.String)
150       0,001     0,113         0,017         public java.lang.String org.anderson.jvm.escapereference.maapratica.Carro.getModelo()
130       0,001     0,084         0,021         public org.anderson.jvm.escapereference.maapratica.Preco org.anderson.jvm.escapereference.maapratica.Carro.getPreco()
10        0,007     0,067         0,022         public void org.anderson.jvm.escapereference.maapratica.Carro.setPreco(java.lang.Double)
20        0,002     0,034         0,006         public java.util.Map org.anderson.jvm.escapereference.maapratica.Preco.getTaxas()
```

Vamos interpretar, do mais impactante para o menos:

1) **`Main.main(...)`**  
   - `calls=1`, `avg=total=max ≈ 90.186 s`  
   - Isso indica que o processo de execução principal levou **~90 segundos** do início ao fim.  
   - Possíveis causas: `Thread.sleep(...)` explícito no fluxo de teste; espera por I/O; breakpoint; tarefa longa executada inline.  
   - **Ação**: verifique se há **esperas artificiais** (sleep) no `main` ou em métodos invocados pelo `main` (cenário didático). Em produção, um `main` demorado geralmente significa **processo batch** ou **boot muito lento**. Quebre o trabalho em **métodos menores** para detalhar a origem: o agente, por padrão, mostra apenas o método instrumentado — se tudo ocorre dentro do `main`, ele acumula ali.

2) **`CarroCollection.printAllCarros()`**  
   - `calls=10`, `avg≈0,911 ms`, `total≈9,109 ms`, `max≈2,392 ms`  
   - É um método de I/O simples (impressões). A média <1ms é ok; o `max` de 2.39ms possivelmente reflete flutuação de console/GC.  
   - **Ação**: Se for apenas logging/print, não é gargalo. Se esse método crescesse em complexidade (buscar de DB, etc.), ficaria relevante.

3) **`Preco.toString()`**  
   - `calls=110`, `avg≈0,013 ms`, `total≈1,425 ms`  
   - É um método barato, mas chamado **muitas vezes**. A soma não preocupa.  
   - **Ação**: Se `toString()` for usado demais em logs enfileirados, **avalie lazy logging** para não montar strings quando o nível de log não imprime.

4) **`CarroCollection.findCarroByModelo(String)`**  
   - `calls=20`, `avg≈0,015 ms`, `total≈0,299 ms`  
   - A busca é bem rápida (provavelmente linear numa lista pequena).  
   - **Ação**: Se a coleção crescer muito em produção, esse método poderia se tornar gargalo; troque para um `Map<modelo, Carro>`.

5) **`Preco.converter(String)`**  
   - `calls=20`, `avg≈0,006 ms`, `total≈0,118 ms`  
   - Conversão muito barata.  
   - **Ação**: Nenhuma necessária.

6) **Getters/Setters (`getModelo`, `getPreco`, `setPreco`)**  
   - Chamados em volume (150, 130, 10), mas todos **baratíssimos**.  
   - **Ação**: Nenhuma.

7) **`Preco.getTaxas()`**  
   - `calls=20`, `avg≈0,002 ms`, `total≈0,034 ms`  
   - Muito barato.  
   - **Ação**: Sem risco; se retornar coleção mutável, somente cuidado com escape/reference (imutabilidade).

**Conclusão do cenário:**  
- O **tempo absoluto** está dominado pelo `main` (provável `Thread.sleep` de demonstração).  
- Entre os demais, **nenhum método** é gargalo real — tempos acumulados baixos.  
- Para diagnósticos mais finos, **quebre tarefas grandes** do `main` em métodos menores, para o agente atribuir o custo a cada passo, e ajuste `include` para cobrir os pacotes relevantes.

**Dica de próxima rodada:**  
- Rode com `minMicros=500` para **reduzir ruído** de getters/setters.  
- Adicione workload de verdade (I/O, DB, HTTP) e observe onde `total(ms)` cresce mais.  
- Considere **exportar periódicos** (flush) para acompanhar long‑running apps.

---

**Gostou?** Você pode usar este artigo como base de documentação interna e como tutorial público no Medium. Se quiser, posso gerar também uma versão **PDF** pronta para download com capa e índice.
