package kopo.poly.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping(value = {"/", "/main"})
    public String main() {
        return "/main/index";
    }
}
