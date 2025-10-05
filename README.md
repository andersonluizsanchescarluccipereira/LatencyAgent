# LatencyAgent

Um **Java Agent** simples e leve para medir **latência de métodos** em tempo de execução, com:

- **Bytecode instrumentation** via [Byte Buddy]
- **Filtro por pacote(s)** (`include`/`exclude`)
- **Limiar mínimo** por duração (`minMicros`)
- **Ranking Top-N**
- **Modo `verbose`** (liga/desliga logs de instrumentação)
- **Exportação em JSON** (para análise/observabilidade)
- **Compatível com Java 8 → 21**

> Pacote principal: `br.com.ads.LatencyAgent`  
> Entrada do agente (MANIFEST): `Premain-Class: br.com.ads.LatencyAgent`

---

## Índice

- [Como funciona (em alto nível)](#como-funciona-em-alto-nível)
- [Recursos](#recursos)
- [Build e empacotamento](#build-e-empacotamento)
- [Como usar (IntelliJ/Terminal)](#como-usar-intellijterminal)
- [Parâmetros (`agentArgs`)](#parâmetros-agentargs)
- [Saída: console e JSON](#saída-console-e-json)
- [Boas práticas e dicas de performance](#boas-práticas-e-dicas-de-performance)
- [Solução de problemas (FAQ)](#solução-de-problemas-faq)
- [Integração com observabilidade](#integração-com-observabilidade)
- [Roadmap (próximas evoluções)](#roadmap-próximas-evoluções)
- [Licença](#licença)

---

## Como funciona (em alto nível)

1. A JVM inicia com `-javaagent:...` e carrega o `LatencyAgent`.
2. O agente configura **matchers** (quais classes serão instrumentadas) com base em `include`/`exclude`.
3. O Byte Buddy intercepta **métodos concretos** (não abstratos, não construtores) e injeta um **`Advice`**:
   - no `@OnMethodEnter`, captura `System.nanoTime()`;
   - no `@OnMethodExit`, computa a duração e registra em `Stats.record(...)`.
4. As métricas são mantidas em um `ConcurrentHashMap` com `LongAdder` (alto throughput).
5. No **shutdown** da JVM, o agente:
   - imprime um ranking **Top-N** dos métodos mais “caros” (tempo total acumulado);
   - exporta um arquivo **JSON** com as métricas.

---

## Recursos

- **Filtros por pacote**: limite o escopo para o seu código (`include=com.sua.app`).
- **`minMicros`**: evita ruído de métodos ultrarrápidos.
- **`top`**: controla quantos métodos vão para o ranking/JSON.
- **`verbose=true`**: debug de quais classes estão sendo **instrumentadas** e **transformadas**.
- **Exportação JSON**: escrita em `latency-metrics.json` (ou caminho customizado via `output=...`).

---

## Build e empacotamento

### Dependências principais
- `net.bytebuddy:byte-buddy`
- `net.bytebuddy:byte-buddy-agent`

### Manifest exigido
O `MANIFEST.MF` do JAR deve conter:
```
Premain-Class: br.com.ads.LatencyAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

> Se usar Maven, configure com `maven-jar-plugin` (e opcionalmente `maven-shade-plugin` para *uber-jar*).

### Comandos
```bash
mvn clean package
# gera: target/agent-1.0-SNAPSHOT.jar
```

---

## Como usar (IntelliJ/Terminal)

### IntelliJ IDEA
1. **Run → Edit Configurations…**
2. Em **VM options**, adicione:
   ```
   -javaagent:/caminho/para/target/agent-1.0-SNAPSHOT.jar=include=org.seu.pacote;minMicros=0;top=20;verbose=true;output=/tmp/latency.json
   ```
3. (Opcional) Em **Working directory**, a raiz do seu projeto/app.
4. **Apply → OK** e rode.

### Terminal
```bash
java  -javaagent:/caminho/para/target/agent-1.0-SNAPSHOT.jar=include=org.seu.pacote;minMicros=0;top=20;verbose=false  -jar seu-app.jar
```

> Dica: comece **sem `include`** e com `minMicros=0` para validar rapidamente; depois refine.

---

## Parâmetros (`agentArgs`)

Formato: `-javaagent:agent.jar=chave=valor;chave=valor;...`

| Parâmetro   | Tipo     | Exemplo                         | Descrição                                                                 |
|-------------|----------|----------------------------------|-----------------------------------------------------------------------------|
| `include`   | lista    | `include=org.app,com.foo`       | Prefixos de pacote a instrumentar. Vazio = instrumentar todos (exceto JDK). |
| `exclude`   | lista    | `exclude=org.app.dto`           | Prefixos de pacote para ignorar.                                           |
| `minMicros` | long     | `minMicros=500`                 | Só registra métodos >= X microssegundos. `0` para tudo.                    |
| `top`       | int      | `top=20`                        | Limite de itens no ranking/JSON.                                           |
| `verbose`   | boolean  | `verbose=true`                  | Loga “Instrumentando/Transformado” no console para depuração.              |
| `output`    | string   | `output=/tmp/latency.json`      | Caminho do arquivo JSON exportado no shutdown.                             |

> Separador: `;` (ponto e vírgula).  
> `include`/`exclude` aceitam múltiplos prefixos separados por vírgula.

---

## Saída: console e JSON

### Console
```
[LatencyAgent] Ativo. min=PT0S, top=20, verbose=true
[LatencyAgent] JVM: 1.8.0_462
[LatencyAgent] Instrumentando: org.app.Main
[LatencyAgent] Transformado:  org.app.service.UserService
...
=== MÉTODOS MAIS LENTOS (por tempo total acumulado) ===
calls     avg(ms)   total(ms)    max(ms)      method
-----------------------------------------------------
120       0.374     44.880       6.120        org.app.service.UserService.save
...
========================================================
[LatencyAgent] Exportado JSON: /tmp/latency.json
```

### JSON (exemplo)
```json
[
  {
    "method": "org.app.Main.main",
    "calls": 5,
    "avg_ms": 2.014,
    "total_ms": 10.071,
    "max_ms": 3.018
  },
  {
    "method": "org.app.service.UserService.save",
    "calls": 120,
    "avg_ms": 0.374,
    "total_ms": 44.880,
    "max_ms": 6.120
  }
]
```

---

## Boas práticas e dicas de performance

- **Sempre use `include`** em produção para limitar o escopo (ex.: `include=org.app`).
- Comece com `minMicros=500` (0,5 ms) para reduzir ruído; ajuste conforme necessidade.
- **Evite** instrumentar pacotes de frameworks/libraries a menos que necessário.
- Use `verbose=true` **só em ambientes de teste** (gera log para cada classe instrumentada).
- Se o app carrega **muitas classes**, prefira filtros específicos para não penalizar o startup.

---

## Solução de problemas (FAQ)

**1) “Failed to find Premain-Class manifest attribute”**  
→ O JAR do agente foi empacotado sem `Premain-Class`.  
**Solução:** ajuste o `pom.xml` (ou build) para gerar o MANIFEST com:  
`Premain-Class: br.com.ads.LatencyAgent`.

**2) “Could not determine current working directory”**  
→ O comando foi executado de um diretório inexistente/sem permissão.  
**Solução:** `cd` para a raiz do projeto/app; ajuste “Working directory” no IntelliJ.

**3) Nenhum método aparece no ranking (tudo vazio)**  
- `include` não bate com seu pacote? Teste sem `include`.  
- `minMicros` está alto demais? Teste com `minMicros=0`.  
- O `transform()` do agente está correto (sem retornar `null`)?  
- Use `verbose=true` para conferir “Instrumentando/Transformado”.

**4) `IllegalAccessError` tentando acessar `LatencyAgent$Stats`**  
→ Classes internas não eram públicas.  
**Solução:** já está resolvido — `Config`, `Stats`, `TimingAdvice` e métodos são `public`.

---

## Integração com observabilidade

- **JSON file**: já exporta um snapshot em `output=<arquivo>`.
  - Fácil de colher por **Loki**, **Fluent Bit**, **Filebeat**, **Datadog Log Agent**.
- **Prometheus / OpenMetrics** (futuro):  
  - Exportador HTTP embutido: expor `/metrics` com counters/histogramas.  
  - Alternativa simples: sidecar que lê o JSON e expõe métricas.

**Sugestão de pipeline simples:**
1. Rodar com `output=/var/log/latency.json`  
2. Um coletor (Fluent Bit, Vector, etc.) envia o JSON para seu backend (Datadog/ELK/Grafana Loki).  
3. Criar dashboard com total por método, p95, max, etc.  
   - (p95/p99 podem vir numa evolução do agente — ver Roadmap.)

---

## Roadmap (próximas evoluções)

- [ ] **Exportador Prometheus embutido** (HTTP)  
- [ ] **Percentis** (p50/p95/p99) por método (sketch/TDigest/HdrHistogram)  
- [ ] **Agregação por classe/pacote** (além de assinatura completa)  
- [ ] **Whitelist/blacklist por regex** de método (ex.: `.*Controller\..*`)  
- [ ] **Heads-up real-time**: flush periódico do JSON (sem esperar shutdown)  
- [ ] **Rotação/limite do mapa** (prevenir crescimento sem controle)  
- [ ] **Múltiplos formatos**: CSV, NDJSON  
- [ ] **Modo amostragem** (sample rate) para reduzir overhead em altíssima carga  
- [ ] **Suporte a attach em runtime** (via `VirtualMachine.loadAgent` com `agentmain`)
---

## Licença

Defina a licença de sua preferência (por exemplo, MIT/Apache 2.0).  
Exemplo (MIT):
```
MIT License — Copyright (c) 2025 ...
```
