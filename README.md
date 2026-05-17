# 18 — CI/CD Pipeline

**Difficulty:** Intermediate (6/10) · **GitHub Actions** · **Docker** · **Kubernetes**

GitHub Actions ile otomatik build/test/deploy pipeline'ı, multi-stage Docker image, Kubernetes deployment ve HPA.

---

## Pipeline Akışı

```
Push/PR → CI Pipeline
            │
            ├── test (Build + JUnit + Integration DB)
            ├── code-quality (Checkstyle, SpotBugs) ←─ parallel
            └── docker-build (sadece main/develop)
                    │
                    └── Trivy vulnerability scan

Tag (v*) → CD Pipeline
            │
            ├── deploy-staging (otomatik)
            │       └── smoke tests
            └── deploy-production (manual approval gerekir)
                    └── GitHub Release oluştur
```

---

## GitHub Actions — Temel Kavramlar

```yaml
on:
  push:
    branches: [ main ]          # main'e push
  pull_request:
    branches: [ main ]          # main'e PR açılınca
  push:
    tags: [ 'v*' ]              # v1.2.3 tag'i
  workflow_dispatch:             # manuel tetik (UI'dan)
  schedule:
    - cron: '0 2 * * 1'        # her Pazartesi 02:00 UTC
```

### Jobs ve Steps
```yaml
jobs:
  my-job:
    runs-on: ubuntu-latest
    needs: [other-job]           # bağımlılık — sıra garantisi
    if: github.ref == 'refs/heads/main'  # koşullu çalıştır
    environment: production      # Environment protection rules

    steps:
      - uses: actions/checkout@v4          # action (marketplace)
      - name: My step
        run: echo "Hello"                  # shell command
        env:
          MY_SECRET: ${{ secrets.MY_SECRET }}  # secrets erişim
```

### Paralel vs Sıralı
```yaml
# Paralel (needs yok)
jobs:
  test: ...
  quality: ...   # test ile aynı anda çalışır

# Sıralı
jobs:
  test: ...
  deploy:
    needs: [test]  # test bitmeden başlamaz
```

---

## Secrets ve Variables

```yaml
# Repository secrets (şifreli)
${{ secrets.DOCKER_PASSWORD }}
${{ secrets.KUBE_CONFIG }}

# GitHub built-in
${{ secrets.GITHUB_TOKEN }}     # repo işlemleri için otomatik
${{ github.actor }}             # tetikleyen kullanıcı
${{ github.repository }}        # owner/repo-name
${{ github.sha }}               # commit SHA
${{ github.ref_name }}          # branch veya tag adı

# Environment variables
${{ env.MY_VAR }}               # env: bloğunda tanımlı
```

---

## Docker Multi-Stage Build

```dockerfile
# Stage 1: Build (JDK + Maven)
FROM eclipse-temurin:21-jdk-alpine AS builder
COPY . .
RUN mvn -B package -DskipTests

# Extract Spring Boot layers
RUN java -Djarmode=layertools -jar target/*.jar extract

# Stage 2: Runtime (sadece JRE — küçük image)
FROM eclipse-temurin:21-jre-alpine AS runtime
USER appuser  # non-root

# Katmanlar değişme sıklığına göre COPY (cache optimizasyonu)
COPY --from=builder dependencies/ ./
COPY --from=builder spring-boot-loader/ ./
COPY --from=builder snapshot-dependencies/ ./
COPY --from=builder application/ ./    # en sık değişen — en son

HEALTHCHECK CMD wget -q --spider http://localhost:8080/actuator/health
```

### Spring Boot Layers (değişme sıklığı)
```
dependencies/          ← 3. party libs (nadiren değişir)
spring-boot-loader/    ← Spring loader (nadiren)
snapshot-dependencies/ ← SNAPSHOT bağımlılıklar
application/           ← Uygulama kodu (sık değişir)
```

### JVM Container Flags
```
-XX:+UseContainerSupport    → cgroup limits'i oku (CPU/RAM)
-XX:MaxRAMPercentage=75.0   → container RAM'inin %75'ini kullan
-XX:+UseG1GC                → G1 garbage collector
```

---

## Kubernetes Deployment

### Rolling Update (zero-downtime)
```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1        # ekstra pod — capacity artırır
    maxUnavailable: 0  # hiç pod düşmez — sıfır downtime
```

### Probes
```yaml
readinessProbe:        # trafik almaya hazır mı?
  httpGet:
    path: /actuator/health/readiness
  initialDelaySeconds: 30

livenessProbe:         # uygulama canlı mı? (restart gerekiyor mu?)
  httpGet:
    path: /actuator/health/liveness
  initialDelaySeconds: 60
```

### HPA (Horizontal Pod Autoscaler)
```yaml
minReplicas: 2
maxReplicas: 10
metrics:
  - cpu: 70%      # CPU %70 → scale out
  - memory: 80%   # Memory %80 → scale out
```

### Resources
```yaml
resources:
  requests:        # scheduler için minimum — bu kadar yer ayır
    memory: "256Mi"
    cpu: "250m"    # 250 millicores = 0.25 CPU
  limits:          # bu limiti aşarsa OOMKilled veya throttle
    memory: "512Mi"
    cpu: "500m"
```

---

## Actuator Endpoints (Kubernetes ile entegre)

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true      # /actuator/health/liveness + /readiness
      show-details: always
```

| Endpoint | Kullanım |
|----------|----------|
| `/actuator/health` | Genel sağlık |
| `/actuator/health/liveness` | K8s liveness probe |
| `/actuator/health/readiness` | K8s readiness probe |
| `/actuator/metrics` | JVM metrikleri |
| `/actuator/info` | Uygulama bilgisi |

---

## Mülakat Soruları

**Q: CI/CD nedir? Continuous Delivery ile Continuous Deployment farkı?**
A: CI (Continuous Integration): Her commit → otomatik build + unit test + static analysis (SonarQube). Amacı: Integration bug'larını erken yakala, "works on my machine" problemini önle. CD (Continuous Delivery): CI başarılı → staging'e otomatik deploy. Production'a ise onaylı (manual trigger) — "her zaman deploy edilebilir" garanti. Continuous Deployment: Her şey otomatik, production'a da. Netflix, Amazon bunu kullanır (günde yüzlerce deploy). Pratik: Çoğu şirket Continuous Delivery (prod için approval gate). Kubernetes + ArgoCD ile GitOps: Git'e merge = deploy trigger (declarative, rollback kolay).

**Q: Docker multi-stage build neden kullanılır? Image boyutunu nasıl küçültür?**
A: Multi-stage build: `FROM maven AS builder` → `FROM eclipse-temurin:21-jre-alpine`. Builder stage'de JDK + Maven + source code. Runtime stage'de sadece JRE + `.jar` — build araçları dahil değil. Fark: Builder image ~400MB, runtime image ~100-150MB. Güvenlik: JDK'da derleme araçları var, saldırı yüzeyi büyük. JRE yeterli — JDK yok. Spring Boot layered jar: Bağımlılıklar (nadiren değişir) ayrı layer, uygulama kodu ayrı layer → Docker cache etkili kullanılır (bağımlılık layer cache'den gelir, sadece kod layer rebuild). Alpine base image: 5MB temel, minimal attack surface.

**Q: `readinessProbe` vs `livenessProbe` vs `startupProbe` farkları nelerdir?**
A: `readinessProbe`: Pod trafik almaya hazır mı? Spring Boot'ta `/actuator/health/readiness`. Uygulama başlarken (DB bağlantısı, cache warmup) trafik gelmemeli — readiness FAIL → Service EndpointSlice'tan çıkarılır. `livenessProbe`: Uygulama çalışıyor mu, restart gerekiyor mu? Deadlock, memory leak → liveness FAIL → `kubectl restart`. `/actuator/health/liveness`. `startupProbe`: Yavaş başlayan uygulamalar için — `livenessProbe` devreye girmeden önce başlama süresi tanı. Başarılı olunca `livenessProbe` devreye girer. Kural: `startupProbe` ile uzun başlangıcı tolere et, `livenessProbe` ile runtime'ı kontrol et, `readinessProbe` ile trafiği yönet.

**Q: HPA (Horizontal Pod Autoscaler) nasıl çalışır? VPA'dan farkı?**
A: HPA: Metrics Server'dan CPU/memory metrics alır (15s interval). `targetAverageUtilization: 70%` → ortalama CPU %70 aşılırsa yeni pod ekle. Scale-down: 5 dakika cooldown (ani spike'ta gereksiz scale-down önlenir). Custom metrics: Kafka consumer lag, HTTP RPS ile de scale edilebilir (KEDA). VPA (Vertical Pod Autoscaler): Pod sayısı değil, resource limit artırır — container'a daha fazla CPU/RAM. HPA ile birlikte kullanım çatışabilir. Öneri: HPA (horizontal) tercih et — stateless uygulama için doğal. Cluster Autoscaler: Node sayısını yönetir (pod eklenince node yetmezse yeni node).

**Q: Kubernetes rolling update stratejisi nedir? Blue-Green'den farkı?**
A: Rolling update: `maxUnavailable: 0, maxSurge: 1` — önce yeni pod başlat, readinessProbe geçince eski pod kapat. Sıfır downtime, kademeli. Dezavantaj: Bir süre eski+yeni versiyon birlikte çalışır (backward compatibility gerekir). Blue-Green deployment: İki ortam (blue=eski, green=yeni). Traffic tümüyle switch edilir. Anında rollback: traffic blue'ya döner. Kaynak: İki kat pod. Canary deployment: Trafiğin %5'ini yeni versiyona yönlendir, metrikler iyi → %100. Ingress veya service mesh (Istio) ile. Kubernetes'te: `kubectl rollout undo deployment/app` ile önceki version'a geri dön.

**Q: GitHub Actions'da matris strateji ve cache nasıl kullanılır?**
A: Matrix strategy: Aynı iş farklı parametrelerle paralel çalışır. `strategy.matrix: {java: [17, 21], os: [ubuntu, windows]}` → 4 job paralel. Multi-version, multi-platform test için. Cache: `actions/cache@v4` ile Maven `.m2` dizinini cache'le — bağımlılıklar her run'da indirilmez. `key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}` — `pom.xml` değişince cache bozulur, yeniden indirilir. GitHub Secrets: Repository → Settings → Secrets. `${{ secrets.DOCKER_PASSWORD }}` ile erişilir, log'da `***` olarak maskelenir, `echo` ile görüntülenemez, fork'larda erişilemez.

---

## Çalıştırma

```bash
# Local build
mvn -B package

# Docker build
docker build -f docker/Dockerfile -t cicd-app .

# Docker Compose
docker compose -f docker/docker-compose.yml up -d

# Kubernetes deploy
kubectl apply -f k8s/deployment.yml

# Health check
curl http://localhost:8080/actuator/health
```
