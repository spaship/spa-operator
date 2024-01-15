package io.spaship.operator.api;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.tuples.Tuple2;
import io.spaship.operator.service.k8s.CommandExecutionService;
import io.spaship.operator.type.CommandExecForm;
import io.spaship.operator.type.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("execute")
@Authenticated
public class CommandExecutionController {
    private static final Logger LOG = LoggerFactory.getLogger(CommandExecutionController.class);

    private final CommandExecutionService exec;
    public CommandExecutionController(CommandExecutionService exec) {
        this.exec = exec;
    }

    @POST
    @Path("/symlink")
    @Produces("text/json")
    @Consumes(MediaType.APPLICATION_JSON)
    public  String createSymlink(CommandExecForm form) {
        LOG.debug("form content is as follows {}", form);
        Tuple2<String, String> sourceTargetTuple = Tuple2.of(form.metadata().get("source"),
                form.metadata().get("target"));
        boolean isCreated = exec.createSymlink(form.environment(), sourceTargetTuple);
        return "{\"created\":"+isCreated+"}";
    }
}