package main

import (
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"github.com/gorilla/mux"
	"io/ioutil"
	"net/http"
	"strings"
	"sync"
	"time"
)

func initImports(router *mux.Router) {
	importInit()
	router.HandleFunc("/import/{id}", doImportGet).Methods(http.MethodGet)
	router.HandleFunc("/import", doImport).Methods(http.MethodPost)
}

// "static" vars
var (
	importOnce  sync.Once
	importCache *CacheClient
)

func importInit() {
	importOnce.Do(func() {
		importCache = NewCache(64, 30)
	})
}

func addNameToFlatFile(flatFile JsonObject, name string) {
	if v, ok := flatFile["app-info"].([]interface{}); ok {
		if ai, ok := v[0].(map[string]interface{}); ok {
			ai["name"] = name
		}
	}
}

const CACHE_LIMIT_ERR = "cache limit exceeded"
const CACHE_FETCH_ERR = "no model for uuid"

func doImport(w http.ResponseWriter, r *http.Request) {
	inbytes, err := ioutil.ReadAll(r.Body)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write(makeErrorResponse("can't read input"))
		return
	}
	var reqobj JsonObject
	err = json.Unmarshal(inbytes, &reqobj)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		w.Write(makeErrorResponse("can't parse input json"))
		return
	}
	payload, ok := reqobj["importFile"].(string)
	if !ok {
		w.WriteHeader(http.StatusBadRequest)
		w.Write(makeErrorResponse("missing importFile key"))
		return
	}

	name, hasName := reqobj["name"].(string)

	result, errstr := tryAllImports(payload)

	var resp []byte
	if result != nil {
		flatFile := bytesToJsonObject(result)
		if hasName {
			addNameToFlatFile(flatFile, name)
		}
		response := make(JsonObject)
		if hasQueryVal(r, "save", "true") {
			guid := uuid.New().String()
			if importCache.Add(guid, flatFile) {
				response["guid"] = guid
				resp = makeSuccessResponse(response)
				w.WriteHeader(http.StatusOK)
			} else {
				resp = makeErrorResponse(CACHE_LIMIT_ERR)
				w.WriteHeader(http.StatusBadRequest)
			}
		} else {
			response["flatFile"] = flatFile
			resp = makeSuccessResponse(response)
			w.WriteHeader(http.StatusOK)
		}
	} else if errstr != "" {
		resp = makeErrorResponse(errstr)
		w.WriteHeader(http.StatusBadRequest)
	} else {
		resp = makeErrorResponse("unexpected converter response")
		w.WriteHeader(http.StatusInternalServerError)
	}
	w.Write(resp)
}

func hasQueryVal(r *http.Request, name string, val string) bool {
	qvals := r.URL.Query()
	if qv, ok := qvals[name]; ok {
		return len(qv) > 0 && qv[0] == val
	}
	return false
}

func doImportGet(w http.ResponseWriter, r *http.Request) {
	id := mux.Vars(r)["id"]
	if cv := importCache.Remove(id); cv != nil {
		flatFile := cv.(JsonObject)
		response := make(JsonObject)
		response["flatFile"] = flatFile
		w.WriteHeader(http.StatusOK)
		w.Write(makeSuccessResponse(response))
	} else {
		w.WriteHeader(http.StatusNotFound)
		w.Write(makeStringResp(false, fmt.Sprintf("%s: %s", CACHE_FETCH_ERR, id)))
	}
}

const notCompose = "Invalid compose file:"
const notk8s1 = "missing kind -- can't validate"
const notk8s2 = "invalid yaml"
const notbundle = "invalid tar input"

var invalids = []string{notCompose, notk8s1, notk8s2, notbundle}

func containsInvalids(val string) bool {
	for i := 0; i < len(invalids); i++ {
		if strings.Index(val, invalids[i]) != -1 {
			return true
		}
	}
	return false
}

func processCvtResults(results []CvtResult) ([]byte, string) {
	errmsg := "Input can't be processed.  " +
		"It must be a parseable YAML file or a " +
		"compressed tar (.tgz, .tar.gz) of parseable YAML files"
	var conversion []byte
	for i := 0; i < len(results); i++ {
		cvt := results[i].converted
		failure := results[i].error
		if cvt != nil {
			conversion = cvt
		} else if !containsInvalids(failure) {
			errmsg = failure
		}
	}
	return conversion, errmsg
}

func tryAllImports(data string) (payload []byte, errstr string) {
	paths := []string{"/k2f", "/kbundle2f", "/c2f"}
	resultchan := make(chan CvtResult)
	for _, p := range paths {
		go doAsyncConvert(p, []byte(data), resultchan)
	}
	results := make([]CvtResult, 0)
	for i := 0; i < len(paths); i++ {
		select {
		case resp := <-resultchan:
			results = append(results, resp)
		case <-time.After(time.Second * 30):
			return nil, "import attempts timed out after 30 seconds"
		}
	}
	close(resultchan)
	return processCvtResults(results)
}
