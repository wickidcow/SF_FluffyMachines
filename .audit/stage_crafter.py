#!/usr/bin/env python3
"""Stage a narrow patch against inspected immutable source; never change a ref."""
from pathlib import Path
import hashlib

p=Path('src/main/java/io/ncbpfluffybear/fluffymachines/objects/AutoCrafter.java')
b=p.read_bytes()
assert hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()=='145e37ef4df3997538d5d1232198467f312a2b3c'
s=b.decode()
def replace(old,new):
    global s
    assert s.count(old)==1, old
    s=s.replace(old,new)
replace('import java.util.concurrent.ConcurrentHashMap;\n','')
replace('    private final Map<String, CachedRecipe> recipeCache = new ConcurrentHashMap<>();\n    private final Object recipeIndexLock = new Object();\n    private volatile Map<Integer, List<IndexedRecipe>> recipeShapeIndex = Map.of();\n    private volatile int indexedRecipeStorageSize = -1;', '    private final RecipeIndexCache<Map<Integer, List<IndexedRecipe>>, CachedRecipe> recipeCache =\n        new RecipeIndexCache<>(this::getRecipeStorageSize, this::buildRecipeIndex);')
replace('            recipeCache.remove(blockData.getKey());','            recipeCache.invalidate(blockData.getKey());')
replace('        if (isInputGridEmpty(menu)) {\n', '        if (isInputGridEmpty(menu)) {\n            recipeCache.invalidate(blockData.getKey());\n')
replace('        CachedRecipe cachedRecipe = recipeCache.get(blockKey);', '        // Refresh the index before consulting either positive or negative results.\n        // Each resolver retains its own generation, so an old resolver cannot\n        // put stale results back into the newly published cache.\n        var generation = recipeCache.current();\n        CachedRecipe cachedRecipe = generation.results.get(blockKey);')
replace('        cachedRecipe = resolveRecipe(blockKey, menu);','        cachedRecipe = resolveRecipe(blockKey, menu, generation);')
replace('    private CachedRecipe resolveRecipe(String blockKey, BlockMenu menu) {', '    private CachedRecipe resolveRecipe(String blockKey, BlockMenu menu,\n            RecipeIndexCache.Generation<Map<Integer, List<IndexedRecipe>>, CachedRecipe> generation) {')
replace('        for (IndexedRecipe recipe : getRecipeCandidates(shape)) {','        for (IndexedRecipe recipe : generation.index.getOrDefault(shape, List.of())) {')
replace('            recipeCache.put(blockKey, resolved);','            generation.results.put(blockKey, resolved);')
replace('        recipeCache.put(blockKey, noMatch);','        generation.results.put(blockKey, noMatch);')
a=s.index('    private List<IndexedRecipe> getRecipeCandidates(int shape) {')
z=s.index('    private int getShape(ItemStack[] items) {',a)
old=s[a:z]
start=old.index('            Map<Integer, List<IndexedRecipe>> newIndex')
end=old.index('            recipeShapeIndex =')
body='\n'.join(line[8:] if line.startswith('        ') else line for line in old[start:end].splitlines())
s=s[:a]+'''    private int getRecipeStorageSize() {
        return mblock.getRecipes().size();
    }

    private Map<Integer, List<IndexedRecipe>> buildRecipeIndex() {
        List<ItemStack[]> recipeStorage = mblock.getRecipes();
        int storageSize = recipeStorage.size();
'''+body+'\n        return Map.copyOf(immutableIndex);\n    }\n\n'+s[z:]
p.write_text(s)
for file,sha in [('pom.xml','4a79dc85951d6a6f54f67f676b755a7b18505083'),('.github/workflows/build.yml','a807e4c64eaa996959e28373994cecca98269e6a')]:
    path=Path(file); data=path.read_bytes()
    assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==sha
    text=data.decode().replace('26.2.13','26.2.14')
    if file.endswith('build.yml'):
        text=text.replace('gh release view "$TAG"','gh release view "$TAG" --repo "$GITHUB_REPOSITORY"').replace('gh release upload "$TAG" "$JAR" --clobber','gh release upload "$TAG" "$JAR" --repo "$GITHUB_REPOSITORY" --clobber').replace('gh release create "$TAG" "$JAR" --target','gh release create "$TAG" "$JAR" --repo "$GITHUB_REPOSITORY" --target')
        start=text.index('          NOTES=')
        end=text.index('\n',start)
        text=text[:start]+'          NOTES="FluffyMachines 26.2.14 refreshes auto-crafter positive and negative recipe results when the provider recipe count changes, and releases cached templates when the grid is emptied. Existing crafting rules, energy use, item identities, saved state, and the 26.2.13 barrel fixes are retained. Minecraft 1.21.11 and Java 21 remain the runtime floor."'+text[end:]
    path.write_text(text)
print('Staged cache-generation change; no refs or release assets updated.')
