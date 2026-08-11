package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.contract.PublicationContract;
import dev.eugene.publicationexporter.contract.PublicationContractWriter;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "write-publication-contract")
public final class WritePublicationContractCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        PublicationContract contract = new PublicationContractWriter().write();
        System.out.println(new ObjectMapper().writeValueAsString(contract));
        return 0;
    }
}
