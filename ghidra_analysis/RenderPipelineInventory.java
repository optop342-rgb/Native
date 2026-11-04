import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.util.exception.CancelledException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

// Run without script arguments for inventory; pass hex addresses to decompile selected entries.
public class RenderPipelineInventory extends GhidraScript {
    private static final Pattern FUNCTION_NAME =
        Pattern.compile("(?i)(renderdragon|bgfx|vulkan|volumetric|atmospher|fog|lightmap)");
    private static final Pattern SYMBOL_NAME =
        Pattern.compile("(?i)(renderdragon|bgfx|vulkan|volumetric|atmospher|fog|lightmap|shader)");
    private static final Pattern STRING_VALUE =
        Pattern.compile("(?i)(^Vulkan$|^OpenGLES$|^Failed to create OpenGLES context|" +
            "^volumetric_fog(_config|_quality)?$|^FogVolumetric(DensitySetting|" +
            "MediaCoeffsSetting|HenyeyGreensteinGSetting)?$|^FogAndDistanceControl$|" +
            "^VolumetricFog(Configuration|Quality)$|^DirectionalLight$|^AmbientLight$|" +
            "^PointLight$|^LightParameters$|^SceneSky$|^AtmosphericScattering$|" +
            "^bgfxbridge Materials$|^GLSL version:|RenderVolumetricFogDescription)");
    private final Map<Long, String> glSlots = new HashMap<>();

    @Override
    public void run() throws Exception {
        String[] arguments = getScriptArgs();
        if (arguments.length > 0) {
            Map<Long, String> requested = new TreeMap<>();
            for (String argument : arguments) {
                long offset = Long.parseUnsignedLong(argument.replace("0x", ""), 16);
                requested.put(offset, "requested");
            }
            decompileCandidates(requested);
            return;
        }

        println("Program: " + currentProgram.getName());
        println("Image base: " + currentProgram.getImageBase());
        for (var block : currentProgram.getMemory().getBlocks()) {
            println("BLOCK\t" + block.getName() + "\t" + block.getStart() + "\t" +
                block.getEnd() + "\t" + block.isExecute());
        }

        int functionCount = 0;
        int matchedFunctions = 0;
        FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
        while (functions.hasNext()) {
            Function function = functions.next();
            functionCount++;
            if (FUNCTION_NAME.matcher(function.getName(true)).find()) {
                println("FUNCTION\t" + function.getEntryPoint() + "\t" + function.getName(true));
                matchedFunctions++;
            }
        }
        println("Defined functions: " + functionCount + "; renderer-named functions: " +
            matchedFunctions);

        SymbolIterator symbols = currentProgram.getSymbolTable().getAllSymbols(true);
        while (symbols.hasNext()) {
            Symbol symbol = symbols.next();
            String name = symbol.getName(true);
            if (SYMBOL_NAME.matcher(name).find() || name.startsWith("<EXTERNAL>::gl")) {
                println("SYMBOL\t" + symbol.getAddress() + "\t" + symbol.getName(true));
                if (name.startsWith("<EXTERNAL>::gl")) {
                    printReferences(symbol);
                }
            }
        }

        int matches = 0;
        Map<Long, String> targetPages = new HashMap<>();
        Map<Long, String> targetStrings = new HashMap<>();
        for (var block : currentProgram.getMemory().getBlocks()) {
            if (block.isExecute() || !block.isInitialized()) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            long start = block.getStart().getOffset();
            for (long offset = 0; offset < block.getSize(); offset++) {
                if (monitor.isCancelled()) {
                    throw new CancelledException();
                }
                int value = currentProgram.getMemory().getByte(block.getStart().add(offset)) & 0xff;
                if (value >= 0x20 && value <= 0x7e) {
                    if (text.length() < 2048) {
                        text.append((char) value);
                    }
                }
                else {
                    if (text.length() >= 5 && STRING_VALUE.matcher(text).find()) {
                        long address = start + offset - text.length();
                        println("STRING\t0x" + Long.toHexString(address) + "\t" + text);
                        targetPages.putIfAbsent(address & ~0xfffL, text.toString());
                        targetStrings.put(address, text.toString());
                        matches++;
                    }
                    text.setLength(0);
                }
            }
        }
        println("Matching strings: " + matches);
        findStringPointers(targetStrings, targetPages);
        for (var entry : glSlots.entrySet()) {
            targetPages.putIfAbsent(entry.getKey() & ~0xfffL, "GL import page");
        }
        findAddressReferences(targetPages, glSlots);
    }

    private void printReferences(Symbol symbol) {
        int count = 0;
        ReferenceIterator references =
            currentProgram.getReferenceManager().getReferencesTo(symbol.getAddress());
        while (references.hasNext() && count < 30) {
            Reference reference = references.next();
            Function caller =
                currentProgram.getFunctionManager().getFunctionContaining(reference.getFromAddress());
            glSlots.put(reference.getFromAddress().getOffset(), symbol.getName(true));
            println("GLREF\t" + reference.getFromAddress() + "\t" +
                (caller == null ? "<unresolved>" : caller.getName(true)) + "\t" +
                symbol.getName(true));
            count++;
        }
    }

    private void findStringPointers(Map<Long, String> targetStrings,
            Map<Long, String> targetPages) throws Exception {
        int found = 0;
        byte[] bytes = new byte[1 << 20];
        for (var block : currentProgram.getMemory().getBlocks()) {
            String name = block.getName();
            if (!block.isInitialized() ||
                    !(name.equals(".data.rel.ro") || name.equals(".data") ||
                    name.equals(".got") || name.equals(".got.plt") ||
                    name.equals(".init_array"))) {
                continue;
            }
            long offset = 0;
            while (offset + 8 <= block.getSize()) {
                monitor.checkCancelled();
                int length = (int) Math.min(bytes.length, block.getSize() - offset);
                length &= ~7;
                currentProgram.getMemory().getBytes(block.getStart().add(offset), bytes, 0, length);
                for (int i = 0; i + 8 <= length; i += 8) {
                    long pointer = 0;
                    for (int j = 0; j < 8; j++) {
                        pointer |= (bytes[i + j] & 0xffL) << (j * 8);
                    }
                    String target = targetStrings.get(pointer);
                    if (target == null) {
                        continue;
                    }
                    long slot = block.getStart().getOffset() + offset + i;
                    targetPages.putIfAbsent(slot & ~0xfffL, "pointer table: " + target);
                    println("STRING_PTR\t0x" + Long.toHexString(slot) + "\t0x" +
                        Long.toHexString(pointer) + "\t" + target);
                    found++;
                }
                offset += length;
            }
        }
        println("String pointers: " + found);
    }

    private void findAddressReferences(Map<Long, String> targetPages,
            Map<Long, String> importSlots) throws Exception {
        int found = 0;
        int pltEntriesFound = 0;
        Map<Long, String> candidateStarts = new TreeMap<>();
        Map<Long, String> glFunctionCandidates = new TreeMap<>();
        Map<Long, String> pltEntries = new HashMap<>();
        Set<Long> reportedCalls = new java.util.HashSet<>();
        byte[] bytes = new byte[1 << 20];
        for (var block : currentProgram.getMemory().getBlocks()) {
            if (!block.getName().equals(".text") && !block.getName().equals(".plt")) {
                continue;
            }
            long offset = 0;
            while (offset + 4 <= block.getSize()) {
                monitor.checkCancelled();
                int length = (int) Math.min(bytes.length, block.getSize() - offset);
                length &= ~3;
                currentProgram.getMemory().getBytes(block.getStart().add(offset), bytes, 0, length);
                for (int i = 0; i < length; i += 4) {
                    long instruction = (bytes[i] & 0xffL) |
                        ((bytes[i + 1] & 0xffL) << 8) |
                        ((bytes[i + 2] & 0xffL) << 16) |
                        ((bytes[i + 3] & 0xffL) << 24);
                    if ((instruction & 0x9f000000L) != 0x90000000L) {
                        continue;
                    }
                    long immediate = (((instruction >>> 5) & 0x7ffffL) << 2) |
                        ((instruction >>> 29) & 0x3L);
                    if ((immediate & (1L << 20)) != 0) {
                        immediate |= ~((1L << 21) - 1);
                    }
                    long pc = block.getStart().getOffset() + offset + i;
                    long targetPage = (pc & ~0xfffL) + (immediate << 12);
                    String target = targetPages.get(targetPage);
                    var address = block.getStart().add(offset + i);
                    if (target != null && found < 100) {
                        Function containing =
                            currentProgram.getFunctionManager().getFunctionContaining(address);
                        var candidateStart = findPrologue(block, offset + i);
                        println("ADRPREF\t" + address + "\tpage=0x" +
                            Long.toHexString(targetPage) + "\t" + target + "\t" +
                            (containing == null ? "<unresolved>" : containing.getName(true)) +
                            "\tstart=" + (candidateStart == null ? "<not-found>" : candidateStart));
                        if (candidateStart != null) {
                            candidateStarts.putIfAbsent(candidateStart.getOffset(), target);
                        }
                        found++;
                    }
                    if (pltEntriesFound < 200 && i + 16 <= length &&
                            offset + i + 12 < block.getSize()) {
                        int baseRegister = (int) (instruction & 0x1f);
                        for (int step = 1; step <= 3; step++) {
                            long next = readWord(bytes, i + step * 4);
                            if ((next & 0xffc00000L) != 0xf9400000L ||
                                    ((next >>> 5) & 0x1f) != baseRegister) {
                                continue;
                            }
                            long slot = targetPage + (((next >>> 10) & 0xfffL) * 8);
                            String api = importSlots.get(slot);
                            if (api == null || !reportedCalls.add(pc)) {
                                continue;
                            }
                            if (block.getName().equals(".plt")) {
                                pltEntries.put(pc, api);
                                println("GL_PLT_STUB\t" + address + "\t" + api);
                                pltEntriesFound++;
                            }
                            else {
                                long callInstruction = readWord(bytes, i + (step + 1) * 4);
                                boolean isCall = (callInstruction & 0xfffffc1fL) == 0xd63f0000L;
                                if (isCall) {
                                    var start = findPrologue(block, offset + i);
                                    println("GL_INDIRECT_CALL\t" + address + "\t" + api +
                                        "\tstart-guess=" +
                                        (start == null ? "<not-found>" : start));
                                    if (start != null) {
                                        glFunctionCandidates.putIfAbsent(start.getOffset(), api);
                                    }
                                }
                            }
                            break;
                        }
                    }
                }
                offset += length;
            }
        }
        println("ADRP references: " + found);
        println("GLES PLT entries: " + pltEntriesFound);
        findDirectCalls(pltEntries, glFunctionCandidates);
        decompileCandidates(glFunctionCandidates);
        for (var entry : candidateStarts.entrySet()) {
            println("CODE_START_GUESS\t" + currentProgram.getAddressFactory()
                .getDefaultAddressSpace().getAddress(entry.getKey()) + "\t" + entry.getValue());
        }
        println("String-reference start guesses: " + candidateStarts.size());
        println("GLES caller start guesses: " + glFunctionCandidates.size());
    }

    private void decompileCandidates(Map<Long, String> candidates) throws Exception {
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        int processed = 0;
        try {
            for (var entry : candidates.entrySet()) {
                if (processed >= 8) {
                    break;
                }
                var address = currentProgram.getAddressFactory()
                    .getDefaultAddressSpace().getAddress(entry.getKey());
                Function function = currentProgram.getFunctionManager().getFunctionAt(address);
                if (function == null) {
                    disassemble(address);
                    function = createFunction(address, "Script_candidate_" +
                        Long.toHexString(entry.getKey()));
                }
                if (function == null) {
                    println("DECOMPILE_FAILED\t" + address + "\tno function created");
                    continue;
                }
                DecompileResults result = decompiler.decompileFunction(function, 45, monitor);
                if (!result.decompileCompleted() || result.getDecompiledFunction() == null) {
                    println("DECOMPILE_FAILED\t" + address + "\t" +
                        result.getErrorMessage());
                    continue;
                }
                String code = result.getDecompiledFunction().getC();
                println("DECOMPILED_CANDIDATE\t" + address + "\t" + entry.getValue() + "\n" +
                    code.substring(0, Math.min(code.length(), 30000)));
                processed++;
            }
        }
        finally {
            decompiler.dispose();
        }
        println("Decompiled GLES candidate functions: " + processed);
    }

    private void findDirectCalls(Map<Long, String> pltEntries,
            Map<Long, String> functionCandidates) throws Exception {
        int calls = 0;
        Set<Long> reported = new java.util.HashSet<>();
        byte[] bytes = new byte[1 << 20];
        for (var block : currentProgram.getMemory().getBlocks()) {
            if (!block.getName().equals(".text")) {
                continue;
            }
            long offset = 0;
            while (offset + 4 <= block.getSize()) {
                monitor.checkCancelled();
                int length = (int) Math.min(bytes.length, block.getSize() - offset);
                length &= ~3;
                currentProgram.getMemory().getBytes(block.getStart().add(offset), bytes, 0, length);
                for (int i = 0; i < length; i += 4) {
                    long instruction = readWord(bytes, i);
                    if ((instruction & 0xfc000000L) != 0x94000000L) {
                        continue;
                    }
                    long immediate = instruction & 0x03ffffffL;
                    if ((immediate & (1L << 25)) != 0) {
                        immediate |= ~((1L << 26) - 1);
                    }
                    long pc = block.getStart().getOffset() + offset + i;
                    String api = pltEntries.get(pc + (immediate << 2));
                    if (api == null || !reported.add(pc)) {
                        continue;
                    }
                    var caller = block.getStart().add(offset + i);
                    var start = findPrologue(block, offset + i);
                    println("GL_DIRECT_CALL\t" + caller + "\t" + api + "\tstart-guess=" +
                        (start == null ? "<not-found>" : start));
                    if (start != null) {
                        functionCandidates.putIfAbsent(start.getOffset(), api);
                    }
                    calls++;
                }
                offset += length;
            }
        }
        println("GLES direct call sites in .text: " + calls);
    }

    private long readWord(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) |
            ((bytes[offset + 1] & 0xffL) << 8) |
            ((bytes[offset + 2] & 0xffL) << 16) |
            ((bytes[offset + 3] & 0xffL) << 24);
    }

    private ghidra.program.model.address.Address findPrologue(
            ghidra.program.model.mem.MemoryBlock block, long instructionOffset) throws Exception {
        long start = Math.max(0, instructionOffset - 0x2000);
        for (long offset = instructionOffset; offset >= start; offset -= 4) {
            long instruction =
                currentProgram.getMemory().getInt(block.getStart().add(offset)) & 0xffffffffL;
            long masked = instruction & 0xffc07fffL;
            if (masked == 0xa9807bfdL || masked == 0xa9007bfdL ||
                    (instruction & 0xffc003ffL) == 0xd10003ffL) {
                return block.getStart().add(offset);
            }
        }
        return null;
    }
}
