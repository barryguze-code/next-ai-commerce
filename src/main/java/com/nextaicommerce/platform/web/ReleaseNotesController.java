package com.nextaicommerce.platform.web;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

@Controller
public class ReleaseNotesController {
    private final List<Release> releases;

    public ReleaseNotesController(ObjectMapper json) throws IOException {
        try(var input=new ClassPathResource("releases/history.json").getInputStream()){
            releases=Arrays.stream(json.readValue(input,Release[].class)).limit(5).toList();
        }
    }

    @GetMapping("/app/releases")
    String releases(@RequestParam(required=false) String version,Authentication authentication,Model model){
        PageController.addAccessModel(authentication,model);
        model.addAttribute("releases",releases);
        model.addAttribute("release",releases.stream().filter(item->item.version().equals(version))
            .findFirst().orElse(releases.getFirst()));
        return "release-notes";
    }

    public record Release(String version,String title,List<Section> sections){}
    public record Section(String title,List<String> items){}
}
