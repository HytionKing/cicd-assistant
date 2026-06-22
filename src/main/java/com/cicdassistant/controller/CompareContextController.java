package com.cicdassistant.controller;

import com.cicdassistant.entity.CompareContext;
import com.cicdassistant.service.CompareContextService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/compare/contexts")
public class CompareContextController {

    private final CompareContextService service;

    public CompareContextController(CompareContextService service) {
        this.service = service;
    }

    @GetMapping
    public List<CompareContext> list() { return service.listAll(); }

    @GetMapping("/applicable")
    public List<CompareContext> applicable(@RequestParam(required = false) Long repoId) {
        return service.listApplicable(repoId);
    }

    @GetMapping("/{id}")
    public CompareContext get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    public CompareContext create(@RequestBody CompareContext c) { return service.create(c); }

    @PutMapping("/{id}")
    public CompareContext update(@PathVariable Long id, @RequestBody CompareContext c) {
        c.setId(id);
        return service.update(c);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
