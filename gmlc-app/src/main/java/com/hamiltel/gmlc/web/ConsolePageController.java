package com.hamiltel.gmlc.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The operations console is a static single-page application built by Vite.
 * Only its two entry paths need a forward; navigation inside the page is state,
 * so there are no server-side routes to add.
 */
@Controller
public class ConsolePageController {

    @GetMapping({"/console", "/console/"})
    public String console() {
        return "forward:/console/index.html";
    }
}
