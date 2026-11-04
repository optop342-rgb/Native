import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class TraceMinecraftStrings extends GhidraScript {
    private static final String[] DEFAULT_NEEDLES = {
        "CommandParseTables",
        "SlashCommandExecuted",
        "commands.execute.description",
        "CommandOrigin did not have a BlockSource",
        "ActorSetPosSystem",
        "ActorSetPositionRequestComponent",
        "TickSetPositionDimensionChunkMove",
        "LocalPlayerPostMoveSystem",
        "PlayerLocationSender",
        "CameraSetPositionSystem",
        "CameraSetRotationSystem"
    };

    @Override
    public void run() throws Exception {
        String[] needles = getScriptArgs().length == 0 ? DEFAULT_NEEDLES : getScriptArgs();
        Map<String, Set<Address>> matches = new LinkedHashMap<>();
        for (String needle : needles) {
            matches.put(needle, new LinkedHashSet<>());
        }

        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!block.isInitialized() || block.isExecute()) {
                continue;
            }
            for (String needle : needles) {
                byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
                byte[] chunk = new byte[1 << 20];
                long offset = 0;
                int overlap = pattern.length - 1;
                while (offset < block.getSize()) {
                    monitor.checkCancelled();
                    int count = (int) Math.min(chunk.length, block.getSize() - offset);
                    currentProgram.getMemory().getBytes(block.getStart().add(offset), chunk, 0, count);
                    for (int i = 0; i <= count - pattern.length; i++) {
                        boolean equal = true;
                        for (int j = 0; j < pattern.length; j++) {
                            if (chunk[i + j] != pattern[j]) {
                                equal = false;
                                break;
                            }
                        }
                        if (equal) {
                            matches.get(needle).add(block.getStart().add(offset + i));
                        }
                    }
                    if (count <= overlap) {
                        break;
                    }
                    offset += count - overlap;
                }
            }
        }

        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        Set<Address> decompiled = new LinkedHashSet<>();
        try {
            println("IMAGE_BASE\t" + currentProgram.getImageBase());
            for (Map.Entry<String, Set<Address>> entry : matches.entrySet()) {
                for (Address target : entry.getValue()) {
                    println("STRING\t" + target + "\t" + entry.getKey());
                    ReferenceIterator references =
                        currentProgram.getReferenceManager().getReferencesTo(target);
                    while (references.hasNext()) {
                        Reference reference = references.next();
                        Address from = reference.getFromAddress();
                        Function function =
                            currentProgram.getFunctionManager().getFunctionContaining(from);
                        println("REF\t" + from + "\t" + reference.getReferenceType() +
                            "\t" + (function == null ? "<no-function>" : function.getEntryPoint()) +
                            "\t" + (function == null ? "" : function.getName(true)));
                        if (function == null || !decompiled.add(function.getEntryPoint()) ||
                                decompiled.size() > 30) {
                            continue;
                        }
                        DecompileResults result =
                            decompiler.decompileFunction(function, 60, monitor);
                        if (result.decompileCompleted() &&
                                result.getDecompiledFunction() != null) {
                            String code = result.getDecompiledFunction().getC();
                            println("DECOMPILE\t" + function.getEntryPoint() + "\t" +
                                function.getName(true) + "\n" +
                                code.substring(0, Math.min(code.length(), 18000)));
                        }
                    }
                }
            }
        }
        finally {
            decompiler.dispose();
        }
    }
}
