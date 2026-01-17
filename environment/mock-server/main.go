package main

import (
	"crypto/md5"
	"encoding/json"
	"log"
	"math/rand/v2"
	"net/http"
	"strings"
	"sync"
	"time"
)

var (
	// In-memory cache for idempotent SUCCESS responses only
	gatewayCache  = make(map[string]string) // paymentId -> gateway (only successful lookups)
	idbSuccessSet = make(map[string]bool)   // cacheKey -> true (only successful calls)
	pgiSuccessSet = make(map[string]bool)   // paymentId -> true (only successful calls)
	cacheMutex    sync.RWMutex

	// Available gateways
	gateways = []string{"stripe", "adyen", "paypal"}

	// Error probabilities
	esErrorRate  = 0.1
	idbErrorRate = 0.1
	pgiErrorRate = 0.15
)

func main() {
	mux := http.NewServeMux()

	// Elasticsearch
	mux.HandleFunc("GET /elasticsearch/payments/_doc/{paymentId}", handleElasticsearch)

	// IDB Facade
	mux.HandleFunc("POST /idb-facade/api/v1/payments/notify", handleIdbNotify)

	// PGI Gateway
	mux.HandleFunc("POST /pgi-gateway/api/v1/payments/{paymentId}/check-status", handlePgiCheckStatus)

	// Admin
	mux.HandleFunc("GET /admin/cache", handleAdminCache)
	mux.HandleFunc("POST /admin/cache/clear", handleAdminCacheClear)

	// Health
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	log.Println("Mock server starting on :8090")
	log.Println("Error rates: ES=10%, IDB=10%, PGI=15% (errors NOT cached, retries can succeed)")
	log.Println("Endpoints:")
	log.Println("  GET  /elasticsearch/payments/_doc/{paymentId}")
	log.Println("  POST /idb-facade/api/v1/payments/notify")
	log.Println("  POST /pgi-gateway/api/v1/payments/{paymentId}/check-status")
	log.Println("  GET  /admin/cache")
	log.Println("  POST /admin/cache/clear")
	log.Println("  GET  /health")

	if err := http.ListenAndServe(":8090", mux); err != nil {
		log.Fatal(err)
	}
}

func handleElasticsearch(w http.ResponseWriter, r *http.Request) {
	paymentId := r.PathValue("paymentId")
	if paymentId == "" {
		http.Error(w, "Payment ID required", http.StatusBadRequest)
		return
	}

	log.Printf("[ES] Looking up gateway for payment: %s", paymentId)

	// Check if we already have a successful result cached
	cacheMutex.RLock()
	if gateway, exists := gatewayCache[paymentId]; exists {
		cacheMutex.RUnlock()
		log.Printf("[ES] Returning cached gateway '%s' for payment: %s", gateway, paymentId)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"_index": "payments",
			"_id":    paymentId,
			"_source": map[string]string{
				"paymentId":   paymentId,
				"gatewayName": gateway,
			},
		})
		return
	}
	cacheMutex.RUnlock()

	// No cached result - randomly decide if this call fails
	if rand.Float64() < esErrorRate {
		log.Printf("[ES] Random error for payment: %s (will succeed on retry)", paymentId)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]string{"error": "Elasticsearch internal error"})
		return
	}

	// Success - determine gateway and cache it
	gateway := determineGateway(paymentId)

	cacheMutex.Lock()
	gatewayCache[paymentId] = gateway
	cacheMutex.Unlock()

	log.Printf("[ES] Returning gateway '%s' for payment: %s (cached)", gateway, paymentId)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"_index": "payments",
		"_id":    paymentId,
		"_source": map[string]string{
			"paymentId":   paymentId,
			"gatewayName": gateway,
		},
	})
}

func handleIdbNotify(w http.ResponseWriter, r *http.Request) {
	var req struct {
		GatewayName string   `json:"gatewayName"`
		PaymentIds  []string `json:"paymentIds"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	cacheKey := req.GatewayName + ":" + strings.Join(req.PaymentIds, ",")
	log.Printf("[IDB] Notify for gateway '%s' with %d payments: %v", req.GatewayName, len(req.PaymentIds), req.PaymentIds)

	// Check if we already have a successful result cached
	cacheMutex.RLock()
	if idbSuccessSet[cacheKey] {
		cacheMutex.RUnlock()
		log.Printf("[IDB] Returning cached success for key: %s", cacheKey)
		time.Sleep(50 * time.Millisecond)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"status":    "ok",
			"message":   "Payments notified successfully",
			"gateway":   req.GatewayName,
			"count":     len(req.PaymentIds),
			"timestamp": time.Now().UTC().Format(time.RFC3339),
		})
		return
	}
	cacheMutex.RUnlock()

	// No cached result - randomly decide if this call fails
	if rand.Float64() < idbErrorRate {
		log.Printf("[IDB] Random error for key: %s (will succeed on retry)", cacheKey)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]string{"error": "IDB Facade internal error"})
		return
	}

	// Success - cache it
	cacheMutex.Lock()
	idbSuccessSet[cacheKey] = true
	cacheMutex.Unlock()

	time.Sleep(50 * time.Millisecond)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"status":    "ok",
		"message":   "Payments notified successfully",
		"gateway":   req.GatewayName,
		"count":     len(req.PaymentIds),
		"timestamp": time.Now().UTC().Format(time.RFC3339),
	})
}

func handlePgiCheckStatus(w http.ResponseWriter, r *http.Request) {
	paymentId := r.PathValue("paymentId")
	gateway := r.Header.Get("X-Gateway-Name")

	log.Printf("[PGI] Check status for payment '%s' on gateway '%s'", paymentId, gateway)

	// Check if we already have a successful result cached
	cacheMutex.RLock()
	if pgiSuccessSet[paymentId] {
		cacheMutex.RUnlock()
		log.Printf("[PGI] Returning cached success for payment: %s", paymentId)
		time.Sleep(30 * time.Millisecond)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		json.NewEncoder(w).Encode(map[string]any{
			"status":    "accepted",
			"paymentId": paymentId,
			"gateway":   gateway,
			"message":   "Status check triggered",
			"timestamp": time.Now().UTC().Format(time.RFC3339),
		})
		return
	}
	cacheMutex.RUnlock()

	// No cached result - randomly decide if this call fails
	if rand.Float64() < pgiErrorRate {
		log.Printf("[PGI] Random error for payment: %s (will succeed on retry)", paymentId)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]string{"error": "PGI Gateway internal error"})
		return
	}

	// Success - cache it
	cacheMutex.Lock()
	pgiSuccessSet[paymentId] = true
	cacheMutex.Unlock()

	time.Sleep(30 * time.Millisecond)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusAccepted)
	json.NewEncoder(w).Encode(map[string]any{
		"status":    "accepted",
		"paymentId": paymentId,
		"gateway":   gateway,
		"message":   "Status check triggered",
		"timestamp": time.Now().UTC().Format(time.RFC3339),
	})
}

func handleAdminCache(w http.ResponseWriter, _ *http.Request) {
	cacheMutex.RLock()
	defer cacheMutex.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"description":      "Only successful responses are cached",
		"gatewayCacheSize": len(gatewayCache),
		"gatewayCache":     gatewayCache,
		"idbSuccessCount":  len(idbSuccessSet),
		"idbSuccessKeys":   keys(idbSuccessSet),
		"pgiSuccessCount":  len(pgiSuccessSet),
		"pgiSuccessIds":    keys(pgiSuccessSet),
	})
}

func handleAdminCacheClear(w http.ResponseWriter, _ *http.Request) {
	cacheMutex.Lock()
	gatewayCache = make(map[string]string)
	idbSuccessSet = make(map[string]bool)
	pgiSuccessSet = make(map[string]bool)
	cacheMutex.Unlock()

	log.Println("[ADMIN] Cache cleared")

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "cache cleared"})
}

func determineGateway(paymentId string) string {
	// Check for explicit gateway in payment ID
	for _, gw := range gateways {
		if strings.Contains(strings.ToLower(paymentId), gw) {
			return gw
		}
	}
	// Assign based on hash (deterministic)
	hash := md5.Sum([]byte(paymentId))
	return gateways[int(hash[0])%len(gateways)]
}

func keys(m map[string]bool) []string {
	result := make([]string, 0, len(m))
	for k := range m {
		result = append(result, k)
	}
	return result
}
