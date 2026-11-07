'use strict'

var fs = require("node:fs")
var path = require("node:path")

var root = path.resolve(__dirname, "..")
var manifest = JSON.parse(fs.readFileSync(path.join(root, "src", "manifest.json"), "utf8"))
if (manifest.package !== "pt.cpcompanion.smartband10") throw new Error("unexpected Vela package")
if (manifest.config.designWidth !== 106) throw new Error("Smart Band 10 designWidth must be 106")
if (!manifest.features.some(function (feature) { return feature.name === "system.interconnect" })) {
  throw new Error("system.interconnect feature is not declared")
}
if (!fs.existsSync(path.join(root, "src", "app.ux"))) throw new Error("src/app.ux is missing")
if (!fs.existsSync(path.join(root, "src", "pages", "index", "index.ux"))) {
  throw new Error("index page is missing")
}

fs.readdirSync(path.join(root, "fixtures")).filter(function (name) { return name.endsWith(".json") }).forEach(function (name) {
  JSON.parse(fs.readFileSync(path.join(root, "fixtures", name), "utf8"))
})
console.log("Smart Band 10 source validation passed")
