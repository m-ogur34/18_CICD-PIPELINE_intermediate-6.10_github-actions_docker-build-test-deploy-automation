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

**Q: CI/CD farkı?**
A: CI — her commit sonrası build + test + analiz (sürekli entegrasyon). CD — Continuous Delivery: staging'e otomatik, prod'a onaylı. Continuous Deployment: prod'a da otomatik.

**Q: Docker multi-stage build neden kullanılır?**
A: Final image'a build araçları (Maven, JDK) dahil olmaz → küçük, güvenli image. Builder stage'de JDK, runtime stage'de sadece JRE.

**Q: `readinessProbe` vs `livenessProbe` farkı?**
A: Readiness — pod trafik almaya hazır mı (warmup süresinde hayır). Liveness — uygulama çalışıyor mu, restart gerekiyor mu. İkisi birlikte: hazır olmadan trafik gelmiyor, donuksa restart ediliyor.

**Q: HPA nasıl çalışır?**
A: Metrics Server'dan CPU/memory alır. Hedef utilization aşılınca pod sayısını artırır. Azalınca yavaşça düşürür (cooldown).

**Q: `maxUnavailable: 0` ne anlama gelir?**
A: Rolling update sırasında hiçbir pod kapatılmaz (maxSurge ile eklenen pod hazır olunca eski kapanır). Sıfır downtime için.

**Q: GitHub Secrets nasıl kullanılır?**
A: Repository Settings → Secrets. `${{ secrets.NAME }}` ile erişilir. Logda maskelenir, `echo` ile görüntülenemez.

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
